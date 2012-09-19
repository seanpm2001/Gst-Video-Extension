package org.nlogo.extensions.gstvideo

import java.io.File
import org.gstreamer.{ Caps, Element, ElementFactory, Pipeline, State }
import org.nlogo.api.{ Argument, Context, ExtensionException, Syntax }

// The code here used to look like the code from Andres Colubri's GSVideo.  Not anymore. --JAB (9/18/12)
object Camera extends VideoPrimitiveManager {

  private lazy val cameraPipeline = initPipeline()
  override protected lazy val mainBusOwner = cameraPipeline

  private var recorderOpt: Option[Recorder] = None //@ Is there something I can do about the `var`iness?  Recycling of recorders?

  val Image = Util.Image{ appSink.pullBuffer }{ buffer => recorderOpt foreach (_.push(buffer)) }

  override def unload() {
    super.unload()
    cameraPipeline.setState(State.NULL) // GStreamer crashes and warns you about this if you don't do it; needed for element cleanup
    cameraPipeline.dispose()
    recorderOpt foreach (_.dispose())
  }

  private def initPipeline() : Pipeline = {
    val pipeline = new Pipeline("camera-capture")
    super.initBusListeners()
    pipeline
  }

  object InitCamera extends VideoCommand {
    override def getSyntax = Syntax.commandSyntax(Array[Int](Syntax.NumberType, Syntax.NumberType))
    override def perform(args: Array[Argument], context: Context) {

      val patchSize     = context.getAgent.world.patchSize
      val width         = args(0).getDoubleValue * patchSize
      val height        = args(1).getDoubleValue * patchSize

      val webcamSource   = ElementFactory.make("qtkitvideosrc", "capture")
      val colorConverter = generateColorspaceConverter
      val videoFilter    = generateVideoFilter

      videoFilter.setCaps(Caps.fromString("video/x-raw-rgb, endianness=4321, bpp=32, depth=24, red_mask=(int)65280, green_mask=(int)16711680, blue_mask=(int)-16777216"))
      appSink.setCaps(Caps.fromString("video/x-raw-rgb, width=%d, height=%d, bpp=32, depth=24, pixel-aspect-ratio=480/640".format(width.toInt, height.toInt)))

      cameraPipeline.addMany(webcamSource, colorConverter, videoFilter, scale, balance, appSink)
      Element.linkMany(webcamSource, colorConverter, videoFilter, scale, balance, appSink)

    }
  }

  object StartCamera extends VideoCommand {
    override def getSyntax = Syntax.commandSyntax(Array[Int](Syntax.NumberType, Syntax.NumberType))
    override def perform(args: Array[Argument], context: Context) {
      cameraPipeline.setState(State.PLAYING)
    }
  }

  object StopCamera extends VideoCommand {
    override def getSyntax = Syntax.commandSyntax(Array[Int]())
    override def perform(args: Array[Argument], context: Context) {
      cameraPipeline.setState(State.NULL)
    }
  }
  object StartRecording extends VideoCommand {
    override def getSyntax = Syntax.commandSyntax(Array[Int](Syntax.StringType, Syntax.NumberType, Syntax.NumberType))
    override def perform(args: Array[Argument], context: Context) {

      import Codec.Theora, Quality.Medium
      val codec = new Theora(Medium)
      val (propNames, propValues, encoder) = codec.getProps

      val fps       = 30
      val filename  = args(0).getString
      val file      = new File(filename)
      val patchSize = context.getAgent.world.patchSize
      val width     = args(1).getDoubleValue * patchSize
      val height    = args(2).getDoubleValue * patchSize
      val muxer     = Util.determineMuxer(filename) getOrElse (throw new ExtensionException("Unrecognized video container"))

      recorderOpt = Option(new Recorder("Recorder", width.toInt, height.toInt, fps, encoder, propNames, propValues, muxer, file))
      recorderOpt foreach (_.start())

    }
  }

  object StopRecording extends VideoCommand {
    override def getSyntax = Syntax.commandSyntax(Array[Int]())
    override def perform(args: Array[Argument], context: Context) {
      recorderOpt foreach (_.stop())
    }
  }

  object IsRolling extends VideoReporter {
    override def getSyntax = Syntax.reporterSyntax(Syntax.BooleanType)
    override def report(args: Array[Argument], context: Context) : AnyRef = {
      Boolean.box(cameraPipeline.getState == State.PLAYING)
    }
  }

}