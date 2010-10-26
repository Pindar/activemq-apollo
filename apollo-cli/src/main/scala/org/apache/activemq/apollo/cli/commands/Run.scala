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
package org.apache.activemq.apollo.cli.commands

import org.apache.felix.gogo.commands.{Action, Option => option, Argument => argument, Command => command}
import org.osgi.service.command.CommandSession
import java.io.File
import org.fusesource.jansi.Ansi
import org.fusesource.jansi.Ansi.Color._
import org.fusesource.jansi.Ansi.Attribute._

import org.mortbay.jetty.Connector
import org.mortbay.jetty.Handler
import org.mortbay.jetty.Server
import org.mortbay.jetty.nio.SelectChannelConnector
import org.mortbay.jetty.webapp.WebAppContext
import org.apache.commons.logging.LogFactory
import org.apache.activemq.apollo.broker.{BrokerRegistry, Broker, ConfigStore, FileConfigStore}
import org.apache.activemq.apollo.util.ServiceControl
import org.fusesource.hawtdispatch.ScalaDispatch._


/**
 * The apollo create command
 */
@command(scope="apollo", name = "run", description = "runs the broker instance")
class Run extends Action {

  @option(name = "port", description = "The port of the http based administration service")
  var port: Int = 8080

  @option(name = "prefix", description = "The prefix path of the web application.")
  var prefix: String = "/"

  @option(name = "conf", description = "The Apollo configuration file.")
  var conf: File = _

  @option(name = "tmp", description = "A temp directory.")
  var tmp: File = _


  private def ansi= new Ansi()
  private class Failure(msg:String) extends RuntimeException(msg)
  private def error(value:Any) = throw new Failure(value.toString)
  private def system_dir(name:String) = {
    val base_value = System.getProperty(name)
    if( base_value==null ) {
      error("The the %s system property is not set.".format(name))
    }
    val file = new File(System.getProperty("apollo.base"))
    if( !file.isDirectory  ) {
      error("The the %s system property is not set to valid directory path %s".format(name, base_value))
    }
    file
  }


  class RichFile(file:File) {
    def / (path:String) = new File(file, path)
  }
  implicit def toRichFile(file:File):RichFile = new RichFile(file)

  def bold(v:String) = ansi.a(INTENSITY_BOLD).a(v).reset

  def execute(session: CommandSession):AnyRef = {

    def println(value:Any) = session.getConsole.println(value)

    try {
      val home = system_dir("apollo.home")
      val base = system_dir("apollo.base")

      val lib = home / "lib"
      val webapp = lib / lib.list.find( _.matches("""apollo-web-.+-slim.war""")).getOrElse(throw new Failure("war file not found.") )
//      val webapp = home / "webapp"
//
      if( conf == null ) {
        val etc = base / "etc"
        etc.mkdirs
        conf = etc / "apollo.xml"
      }

      if( tmp == null ) {
        tmp = base / "tmp"
        tmp.mkdirs
      }


      println("========================================================================")
      println("Apollo Broker Service Starting");
      println("========================================================================")
      println("")

      // Load the configs and start the brokers up.
      println("Loading configurations from '%s'.".format(conf));
      val store = new FileConfigStore
      store.file = conf
      ConfigStore() = store
      store.start(^{

        // Brokers startup async.
        store.foreachBroker(true) { config=>
          // Only start the broker up if it's enabled..
          if( config.enabled ) {
            println("Starting broker '%s'...".format(config.id));
            val broker = new Broker()
            broker.config = config
            BrokerRegistry.add(config.id, broker)
            broker.start(^{
              println("Broker '%s' started.".format(config.id));
            })
          }
        }

      })


      // Start up the admin interface...
      println("Starting administration interface..."+webapp);
      var server = new Server

      var connector = new SelectChannelConnector
      connector.setPort(port)
      connector.setServer(server)

      var app_context = new WebAppContext
      app_context.setContextPath(prefix)
      app_context.setWar(webapp.getCanonicalPath)
      app_context.setServer(server)
      app_context.setLogUrlOnStart(true)
      app_context.setTempDirectory(tmp)

      server.setHandlers(Array[Handler](app_context))
      server.setConnectors(Array[Connector](connector))
      server.start

      val localPort = connector.getLocalPort
      def url = "http://localhost:" + localPort + prefix
      println("Administration interface available at: "+bold(url))

    } catch {
      case x:Failure=>
        println(ansi.a(INTENSITY_BOLD).fg(RED).a("ERROR: ").reset.a(x.getMessage))
    }
    null
  }

//  def stop: Unit = {
//    server.stop
//  }

}