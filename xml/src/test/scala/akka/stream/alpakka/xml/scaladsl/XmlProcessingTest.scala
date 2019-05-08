/*
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.alpakka.xml.scaladsl

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.alpakka.xml._
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.util.ByteString
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}

import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration._

class XmlProcessingTest extends WordSpec with Matchers with BeforeAndAfterAll {
  implicit val system = ActorSystem("Test")
  implicit val mat = ActorMaterializer()

  // #parser
  val parse = Flow[String]
    .map(ByteString(_))
    .via(XmlParsing.parser)
    .toMat(Sink.seq)(Keep.right)
  // #parser

  "XML Parser" must {

    "properly parse simple XML" in {
      // #parser-usage
      val doc = "<doc><elem>elem1</elem><elem>elem2</elem></doc>"
      val resultFuture = Source.single(doc).runWith(parse)
      // #parser-usage

      val result = Await.result(resultFuture, 3.seconds)
      result should ===(
        List(
          StartDocument,
          StartElement("doc"),
          StartElement("elem"),
          Characters("elem1"),
          EndElement("elem"),
          StartElement("elem"),
          Characters("elem2"),
          EndElement("elem"),
          EndElement("doc"),
          EndDocument
        )
      )
    }

    "properly process a comment" in {
      val doc = "<doc><!--comment--></doc>"

      val resultFuture = Source.single(doc).runWith(parse)

      val result = Await.result(resultFuture, 3.seconds)
      result should ===(
        List(
          StartDocument,
          StartElement("doc"),
          Comment("comment"),
          EndElement("doc"),
          EndDocument
        )
      )
    }

    "properly process parse instructions" in {
      val doc = """<?target content?><doc></doc>"""

      val resultFuture = Source.single(doc).runWith(parse)

      val result = Await.result(resultFuture, 3.seconds)
      result should ===(
        List(
          StartDocument,
          ProcessingInstruction(Some("target"), Some("content")),
          StartElement("doc"),
          EndElement("doc"),
          EndDocument
        )
      )

    }

    "properly process attributes" in {
      val doc = """<doc good="yes"><elem nice="yes" very="true">elem1</elem></doc>"""

      val resultFuture = Source.single(doc).runWith(parse)

      val result = Await.result(resultFuture, 3.seconds)
      result should ===(
        List(
          StartDocument,
          StartElement("doc", Map("good" -> "yes")),
          StartElement("elem", Map("nice" -> "yes", "very" -> "true")),
          Characters("elem1"),
          EndElement("elem"),
          EndElement("doc"),
          EndDocument
        )
      )
    }

    "properly process default namespace" in {
      val doc = """<doc xmlns="test:xml:0.1"><elem>elem1</elem><elem>elem2</elem></doc>"""

      val resultFuture = Source.single(doc).runWith(parse)

      val result = Await.result(resultFuture, 3.seconds)
      result should ===(
        List(
          StartDocument,
          StartElement("doc",
                       namespace = Some("test:xml:0.1"),
                       prefix = None,
                       namespaceCtx = List(Namespace("test:xml:0.1"))),
          StartElement("elem", namespace = Some("test:xml:0.1")),
          Characters("elem1"),
          EndElement("elem"),
          StartElement("elem", namespace = Some("test:xml:0.1")),
          Characters("elem2"),
          EndElement("elem"),
          EndElement("doc"),
          EndDocument
        )
      )
    }

    "properly process prefixed  namespace" in {
      val doc = """<x xmlns:edi="http://ecommerce.example.org/schema"></x>"""

      val resultFuture = Source.single(doc).runWith(parse)

      val result = Await.result(resultFuture, 3.seconds)
      result should ===(
        List(
          StartDocument,
          StartElement("x",
                       namespace = None,
                       prefix = None,
                       namespaceCtx = List(Namespace("http://ecommerce.example.org/schema", prefix = Some("edi")))),
          EndElement("x"),
          EndDocument
        )
      )
    }
    "properly process multiple namespaces" in {
      val doc =
        """<?xml version="1.0"?><bk:book xmlns:bk='urn:loc.gov:books' xmlns:isbn='urn:ISBN:0-395-36341-6'><bk:title>Cheaper by the Dozen</bk:title><isbn:number>1568491379</isbn:number></bk:book>"""

      val resultFuture = Source.single(doc).runWith(parse)

      val result = Await.result(resultFuture, 3.seconds)
      result should ===(
        List(
          StartDocument,
          StartElement(
            "book",
            namespace = Some("urn:loc.gov:books"),
            prefix = Some("bk"),
            namespaceCtx = List(Namespace("urn:loc.gov:books", prefix = Some("bk")),
                                Namespace("urn:ISBN:0-395-36341-6", prefix = Some("isbn")))
          ),
          StartElement(
            "title",
            namespace = Some("urn:loc.gov:books"),
            prefix = Some("bk")
          ),
          Characters("Cheaper by the Dozen"),
          EndElement("title"),
          StartElement(
            "number",
            namespace = Some("urn:ISBN:0-395-36341-6"),
            prefix = Some("isbn")
          ),
          Characters("1568491379"),
          EndElement("number"),
          EndElement("book"),
          EndDocument
        )
      )
    }

    "properly process attributes with prefix and namespace" in {
      val doc =
        """<x xmlns:edi='http://ecommerce.example.org/schema'><lineItem edi:taxClass="exempt">Baby food</lineItem></x>"""
      val resultFuture = Source.single(doc).runWith(parse)

      val result = Await.result(resultFuture, 3.seconds)
      result should ===(
        List(
          StartDocument,
          StartElement("x",
                       namespaceCtx = List(Namespace("http://ecommerce.example.org/schema", prefix = Some("edi")))),
          StartElement(
            "lineItem",
            List(Attribute("taxClass", "exempt", Some("edi"), Some("http://ecommerce.example.org/schema")))
          ),
          Characters("Baby food"),
          EndElement("lineItem"),
          EndElement("x"),
          EndDocument
        )
      )
    }

    "properly process CData blocks" in {
      val doc = """<doc><![CDATA[<not>even</valid>]]></doc>"""

      val resultFuture = Source.single(doc).runWith(parse)

      val result = Await.result(resultFuture, 3.seconds)
      result should ===(
        List(
          StartDocument,
          StartElement("doc"),
          CData("<not>even</valid>"),
          EndElement("doc"),
          EndDocument
        )
      )
    }

    "properly parse large XML" in {
      val elements = immutable.Iterable.range(0, 10).map(_.toString)

      val documentStream =
        Source
          .single("<doc>")
          .concat(Source(elements).intersperse("<elem>", "</elem><elem>", "</elem>"))
          .concat(Source.single("</doc>"))

      val resultFuture = documentStream
        .map(ByteString(_))
        .via(XmlParsing.parser)
        .filter {
          case EndDocument => false
          case StartDocument => false
          case EndElement("elem") => false
          case _ => true
        }
        .splitWhen(_ match {
          case StartElement("elem", _, _, _, _) => true
          case _ => false
        })
        .collect({
          case Characters(s) => s
        })
        .concatSubstreams
        .runWith(Sink.seq)

      val result = Await.result(resultFuture, 3.seconds)
      result should ===(elements)
    }
  }

  override protected def afterAll(): Unit = system.terminate()
}