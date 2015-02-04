package filodb.core.cassandra

import com.websudos.phantom.testing.CassandraFlatSpec
import org.scalatest.BeforeAndAfter
import scala.concurrent.Await
import scala.concurrent.duration._

import filodb.core.metadata.Partition
import filodb.core.messages._

class PartitionTableSpec extends CassandraFlatSpec with BeforeAndAfter {
  val keySpace = "test"

  // First create the partitions table
  override def beforeAll() {
    super.beforeAll()
    // Note: This is a CREATE TABLE IF NOT EXISTS
    Await.result(PartitionTable.create.future(), 3 seconds)
  }

  before {
    Await.result(PartitionTable.truncate.future(), 3 seconds)
  }

  import scala.concurrent.ExecutionContext.Implicits.global

  val p = Partition("foo", "first")
  val pp = p.copy(firstRowId = Seq(0), versionRange = Seq((0, 1)))

  "PartitionTable" should "create an empty Partition if not exists only" in {
    whenReady(PartitionTable.newPartition(p)) { response =>
      response should equal (Success)
    }

    whenReady(PartitionTable.newPartition(p)) { response =>
      response should equal (AlreadyExists)
    }
  }

  it should "not allow adding an invalid or not empty Partition" in {
    whenReady(PartitionTable.newPartition(pp)) { response =>
      response should equal (Partition.NotEmpty)
    }

    whenReady(PartitionTable.newPartition(p.copy(chunkSize = 0))) { response =>
      response should equal (Partition.NotValid)
    }
  }

  it should "return full Partition information if found" in {
    whenReady(PartitionTable.newPartition(p)) { response =>
      response should equal (Success)
    }

    whenReady(PartitionTable.getPartition("foo", "first")) { response =>
      response should equal (Partition.ThePartition(p))
    }
  }

  it should "return NotFound for GetPartition if dataset & partition not found" in {
    whenReady(PartitionTable.getPartition("no", "no")) { response =>
      response should equal (NotFound)
    }
  }

  it should "add a shard" in {
    whenReady(PartitionTable.newPartition(p)) { response =>
      response should equal (Success)
    }

    val f = PartitionTable.addShard(p, 0, 0 -> 1)
    whenReady(f) { response => response should equal (Success) }
  }

  it should "not add && return InconsistentState if the dataset and partition do not exist" in {
    val f = PartitionTable.addShard(p.copy(partition = "nosuchPart"), 0, 0 -> 1)
    whenReady(f) { response => response should equal (InconsistentState) }
  }

  it should "return NotValid if an invalid shard was added to a Partition" in {
    val f = PartitionTable.addShard(pp, -1, 1 -> 2)
    whenReady(f) { response => response should equal (Partition.NotValid) }
  }

  it should "return InconsistentState if partition state changed underneath" in {
    whenReady(PartitionTable.newPartition(p)) { response =>
      response should equal (Success)
    }

    // Now, pretend to add a shard as a different user, but original user thinks we are
    // still at partition state p.  Therefore, even tho adding a shard to p is valid from p's
    // perspective, it will no longer be valid from the perspective of what is actually on disk.
    // The hash would have changed on disk, so one can no longer add a shard to the original p.
    val f = PartitionTable.addShard(p, 20, 0 -> 2)
    whenReady(f) { response => response should equal (Success) }

    val f2 = PartitionTable.addShard(p, 10, 0 -> 1)
    whenReady(f2) { response => response should equal (InconsistentState) }
  }
}