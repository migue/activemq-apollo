package org.apache.activemq.apollo.broker.store

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import org.fusesource.hawtbuf.AsciiBuffer._
import org.fusesource.hawtdispatch.TaskTracker
import java.util.concurrent.TimeUnit
import collection.mutable.ListBuffer
import org.apache.activemq.apollo.util.{LoggingTracker, FunSuiteSupport, LongCounter}
import org.scalatest.BeforeAndAfterEach
import org.apache.activemq.apollo.util.FileSupport._
import java.util.concurrent.atomic.AtomicReference
import java.io._
import org.apache.activemq.apollo.util.sync_cb

/**
 * <p>Implements generic testing of Store implementations.</p>
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
abstract class StoreFunSuiteSupport extends FunSuiteSupport with BeforeAndAfterEach {

  var store:Store = null

  def create_store(flushDelay:Long):Store

  /**
   * Handy helper to call an async method on the store and wait for
   * the result of the callback.
   */


  def data_directory = basedir / "target" / "apollo-data"

  override protected def beforeAll() = {
    super.beforeAll()
    data_directory.recursive_delete
    store = create_store(5*1000)
    val tracker = new LoggingTracker("store startup")
    tracker.start(store)
    tracker.await
  }

  override protected def afterAll() = {
    val tracker = new LoggingTracker("store stop")
    tracker.stop(store)
    tracker.await
    super.afterAll()
  }

  override protected def beforeEach() = {
    purge
  }

  def purge {
    val tracker = new LoggingTracker("store startup")
    val task = tracker.task("purge")
    store.purge(task.run)
    tracker.await
  }


  def expectCB[T](expected:T)(func: (T=>Unit)=>Unit ) = {
    expect(expected) {
      sync_cb(func)
    }
  }

  val queue_key_counter = new LongCounter

  def add_queue(name:String):Long = {
    var queue_a = QueueRecord(queue_key_counter.incrementAndGet, ascii("test"), ascii(name))
    val rc:Boolean = sync_cb( cb=> store.add_queue(queue_a)(cb) )
    expect(true)(rc)
    queue_a.key
  }

  def add_message(batch:StoreUOW, content:String) = {
    var message = new MessageRecord
    message.protocol = ascii("test-protocol")
    message.buffer = ascii(content).buffer
    message.locator = new AtomicReference[Object]()
    val key = batch.store(message)
    (key, message.locator)
  }


  def entry(queue_key:Long, entry_seq:Long, message_key:(Long, AtomicReference[Object])) = {
    var queueEntry = new QueueEntryRecord
    queueEntry.queue_key = queue_key
    queueEntry.entry_seq = entry_seq
    queueEntry.message_key = message_key._1
    queueEntry.message_locator = message_key._2
    queueEntry
  }

  def populate(queue_key:Long, messages:List[String], first_seq:Long=1) = {
    var batch = store.create_uow
    var msg_keys = ListBuffer[(Long, AtomicReference[Object], Long)]()
    var next_seq = first_seq

    messages.foreach { message=>
      val msgKey = add_message(batch, message)
      msg_keys +=( (msgKey._1, msgKey._2, next_seq) )
      batch.enqueue(entry(queue_key, next_seq, msgKey))
      next_seq += 1
    }

    val tracker = new TaskTracker()

    val task = tracker.task("uow complete")
    batch.on_complete(task.run)
    batch.release

    msg_keys.foreach { msgKey =>
      store.flush_message(msgKey._1) {}
    }
    tracker.await
    msg_keys
  }

  test("add and list queues") {
    val A = add_queue("A")
    val B = add_queue("B")
    val C = add_queue("C")

    val seq:Seq[Long] = List(A,B,C).toSeq
    expectCB(seq) { cb=>
      store.list_queues(cb)
    }
  }

  test("export and import") {
    val A = add_queue("A")
    val msg_keys = populate(A, "message 1"::"message 2"::"message 3"::Nil)

    val rc:Option[MessageRecord] = sync_cb( cb=> store.load_message(msg_keys.head._1, msg_keys.head._2)(cb) )
    expect(ascii("message 1").buffer) {
      rc.get.buffer
    }

    val file = test_data_dir / "export.tgz"
    file.getParentFile.mkdirs()
    using( new BufferedOutputStream(new FileOutputStream(file))) { os =>
      // Export the data...
      expect(None) {
        sync_cb[Option[String]] { cb =>
          store.export_data(os, cb)
        }
      }
    }

    // purge the data..
    purge

    // There should ne no queues..
    expectCB(Seq[Long]()) { cb=>
      store.list_queues(cb)
    }

    // Import the data..
    using(new BufferedInputStream(new FileInputStream(file))) { is =>
      expect(None) {
        sync_cb[Option[String]] { cb =>
          store.import_data(is, cb)
        }
      }
    }

    // The data should be there now again..
    val queues:Seq[Long] = sync_cb(store.list_queues(_))
    expect(1)(queues.size)
    val entries:Seq[QueueEntryRecord] = sync_cb(cb=> store.list_queue_entries(A,0, Long.MaxValue)(cb))
    expect(3) ( entries.size  )

  }

  test("load stored message") {
    val A = add_queue("A")
    val msg_keys = populate(A, "message 1"::"message 2"::"message 3"::Nil)

    val rc:Option[MessageRecord] = sync_cb( cb=> store.load_message(msg_keys.head._1, msg_keys.head._2)(cb) )
    expect(ascii("message 1").buffer) {
      rc.get.buffer
    }
  }

  test("get queue status") {
    val A = add_queue("my queue name")
    populate(A, "message 1"::"message 2"::"message 3"::Nil)

    val rc:Option[QueueRecord] = sync_cb( cb=> store.get_queue(A)(cb) )
    expect(ascii("my queue name")) {
      rc.get.binding_data.ascii
    }
  }

  test("list queue entries") {
    val A = add_queue("A")
    val msg_keys = populate(A, "message 1"::"message 2"::"message 3"::Nil)

    val rc:Seq[QueueEntryRecord] = sync_cb( cb=> store.list_queue_entries(A,0, Long.MaxValue)(cb) )
    expect(msg_keys.toSeq.map(_._3)) {
      rc.map( _.entry_seq )
    }
  }

  test("batch completes after a delay") {x}
  def x = {
    val A = add_queue("A")
    var batch = store.create_uow

    val m1 = add_message(batch, "message 1")
    batch.enqueue(entry(A, 1, m1))

    val tracker = new TaskTracker()
    val task = tracker.task("uow complete")
    batch.on_complete(task.run)
    batch.release

    expect(false) {
      tracker.await(3, TimeUnit.SECONDS)
    }
    expect(true) {
      tracker.await(5, TimeUnit.SECONDS)
    }
  }

  test("flush cancels the delay") {
    val A = add_queue("A")
    var batch = store.create_uow

    val m1 = add_message(batch, "message 1")
    batch.enqueue(entry(A, 1, m1))

    val tracker = new TaskTracker()
    val task = tracker.task("uow complete")
    batch.on_complete(task.run)
    batch.release

    store.flush_message(m1._1) {}

    expect(true) {
      tracker.await(1, TimeUnit.SECONDS)
    }
  }


}
