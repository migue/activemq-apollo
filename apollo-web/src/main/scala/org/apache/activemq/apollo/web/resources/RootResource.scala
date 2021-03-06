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
package org.apache.activemq.apollo.web.resources

import javax.ws.rs._
import core.Response.Status._
import core.Response
import javax.servlet.http.HttpServletRequest
import com.sun.jersey.server.impl.ThreadLocalInvoker
import org.apache.activemq.apollo.web.WebModule
import javax.ws.rs.core.MediaType._

/**
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
@Path("/")
case class RootResource() extends Resource() {
  import WebModule._

  @GET
  @Produces(Array(APPLICATION_JSON, APPLICATION_XML,TEXT_XML,TEXT_HTML))
  def post_connection_shutdown_and_redirect() = {
    Response.seeOther(strip_resolve(root_redirect)).build
  }

  @Path("{name}")
  def path(@PathParam("name") name:String):AnyRef = {

    // Jersey's thread local wrapping messes /w us since we may use the http_request
    // in a different thread.  Lets try to unwrap it..
    try {
      val invoker = java.lang.reflect.Proxy.getInvocationHandler(http_request).asInstanceOf[ThreadLocalInvoker[HttpServletRequest]]
      http_request = invoker.get()
    } catch {
      case e:Throwable => e.printStackTrace()
    }

    web_resources.get(name).map{func =>
      val resource = func()
      resource match {
        case resource:Resource=>
          // Wishing jersey would just re-inject the sub resources
          resource.copy(this)
        case _ =>
      }
      resource
    }.getOrElse(result(NOT_FOUND))
  }

}
