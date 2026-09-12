package io.worxbend.nerdfonts.install

import io.worxbend.nerdfonts.discard

import scala.collection.mutable.ArrayBuffer

/**
 * A deliberately unsynchronised sink: the `InstallEventSink` contract promises one caller at a time, and a
 * plain buffer is how a test notices if the engine ever breaks that promise. `onEvent` lets a test react to
 * an event (release a latch) without subclassing.
 */
final private[install] class RecordingSink(onEvent: InstallEvent => Unit = _ => ()) extends InstallEventSink:
  private val recorded = ArrayBuffer.empty[InstallEvent]

  def emit(event: InstallEvent): Unit =
    recorded.append(event).discard
    onEvent(event)

  def events: Vector[InstallEvent] = recorded.toVector
