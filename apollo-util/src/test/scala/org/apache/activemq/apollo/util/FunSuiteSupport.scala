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

package org.apache.activemq.apollo.util

import _root_.org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import java.io.File
import java.lang.String
import collection.immutable.Map
import org.scalatest._
import java.util.concurrent.TimeUnit
import FileSupport._

/**
 * @version $Revision : 1.1 $
 */
@RunWith(classOf[JUnitRunner])
abstract class FunSuiteSupport extends FunSuite with Logging with BeforeAndAfterAll {
  protected var _basedir = try {
    var file = new File(getClass.getProtectionDomain.getCodeSource.getLocation.getFile)
    file = (file / ".." / "..").getCanonicalFile
    if( file.isDirectory ) {
      file.getPath
    } else {
      "."
    }
  } catch {
    case x=>
      "."
  }

  /**
   * Returns the base directory of the current project
   */
  def basedir = new File(_basedir).getCanonicalFile

  /**
   * Returns ${basedir}/target/test-data
   */
  def test_data_dir = basedir / "target"/ "test-data"

  override protected def beforeAll(map: Map[String, Any]): Unit = {
    _basedir = map.get("basedir") match {
      case Some(basedir) =>
        basedir.toString
      case _ =>
        System.getProperty("basedir", _basedir)
    }
    System.setProperty("basedir", _basedir)
    System.setProperty("testdatadir", test_data_dir.getCanonicalPath)
    test_data_dir.recursive_delete
    super.beforeAll(map)
  }

  //
  // Allows us to get the current test name.
  //

  val _testName = new ThreadLocal[String]();

  def testName = _testName.get

  protected override def runTest(testName: String, reporter: org.scalatest.Reporter, stopper: Stopper, configMap: Map[String, Any], tracker: Tracker) = {
    _testName.set(testName)
    try {
      super.runTest(testName, reporter, stopper, configMap, tracker)
    } finally {
      _testName.remove
    }
  }

  private class ShortCircuitFailure(msg:String) extends RuntimeException(msg)

  def exit_within_with_failure[T](msg:String):T = throw new ShortCircuitFailure(msg)

  def within[T](timeout:Long, unit:TimeUnit)(func: => Unit ):Unit = {
    val start = System.currentTimeMillis
    var amount = unit.toMillis(timeout)
    var sleep_amount = amount / 100
    var last:Throwable = null

    if( sleep_amount < 1 ) {
      sleep_amount = 1
    }
    try {
      func
      return
    } catch {
      case e:ShortCircuitFailure => throw e
      case e:Throwable => last = e
    }

    while( (System.currentTimeMillis-start) < amount ) {
      Thread.sleep(sleep_amount)
      try {
        func
        return
      } catch {
        case e:ShortCircuitFailure => throw e
        case e:Throwable => last = e
      }
    }

    throw last
  }
}