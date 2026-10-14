package com.example.aistudiomv

import android.content.Context
import android.graphics.*
import android.media.*
import android.net.Uri
import android.os.SystemClock
import java.io.FileDescriptor
import java.nio.ByteBuffer

/** Native Android video encoder: each photo is shown for 3 seconds, at 24 fps. */
class VideoMaker(private val context:Context) {
    fun make(images:List<Uri>, audio:Uri?, output:FileDescriptor) {
        val width=1280; val height=720; val fps=24; val secondsPerPhoto=3
        val durationUs=images.size.toLong()*secondsPerPhoto*1_000_000L
        val format=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,width,height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE,2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE,fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
        }
        val encoder=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer=MediaMuxer(output,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var extractor:MediaExtractor?=null
        var videoTrack=-1; var audioTrack=-1; var started=false
        try {
            if(audio!=null) {
                extractor=MediaExtractor()
                context.contentResolver.openAssetFileDescriptor(audio,"r")!!.use { afd ->
                    extractor!!.setDataSource(afd.fileDescriptor,afd.startOffset,afd.length)
                }
                val index=(0 until extractor!!.trackCount).firstOrNull { extractor!!.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true }
                if(index!=null){
                    val audioFormat=extractor!!.getTrackFormat(index)
                    val mime=audioFormat.getString(MediaFormat.KEY_MIME)
                    if(mime!=MediaFormat.MIMETYPE_AUDIO_AAC) error("이 버전의 음악 합성은 AAC/M4A만 지원합니다. MP3는 AAC/M4A로 변환 후 선택하세요.")
                    audioTrack=muxer.addTrack(audioFormat); extractor!!.selectTrack(index)
                }
            }
            encoder.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface=encoder.createInputSurface()
            encoder.start()
            val info=MediaCodec.BufferInfo()
            fun drain(final:Boolean) {
                while(true) {
                    val idx=encoder.dequeueOutputBuffer(info,if(final)10000 else 0)
                    when(idx){
                        MediaCodec.INFO_TRY_AGAIN_LATER -> if(!final)return
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { videoTrack=muxer.addTrack(encoder.outputFormat); muxer.start(); started=true }
                        else -> if(idx>=0){
                            val data=encoder.getOutputBuffer(idx)!!
                            if(info.size>0 && started){ data.position(info.offset); data.limit(info.offset+info.size); muxer.writeSampleData(videoTrack,data,info) }
                            encoder.releaseOutputBuffer(idx,false)
                            if(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM !=0)return
                        }
                    }
                }
            }
            for(uri in images){
                val bmp=context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) } ?: error("사진을 읽을 수 없습니다")
                repeat(secondsPerPhoto*fps){
                    val canvas=surface.lockCanvas(null)
                    try {
                        canvas.drawColor(Color.BLACK)
                        val scale=minOf(width.toFloat()/bmp.width,height.toFloat()/bmp.height)
                        val dw=bmp.width*scale; val dh=bmp.height*scale
                        val dst=RectF((width-dw)/2,(height-dh)/2,(width+dw)/2,(height+dh)/2)
                        canvas.drawBitmap(bmp,null,dst,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    } finally { surface.unlockCanvasAndPost(canvas) }
                    drain(false)
                }
                bmp.recycle()
            }
            encoder.signalEndOfInputStream()
            drain(true)
            if(audioTrack>=0 && extractor!=null){
                val buffer=ByteBuffer.allocateDirect(1024*1024)
                val audioInfo=MediaCodec.BufferInfo()
                while(true){
                    buffer.clear()
                    val size=extractor!!.readSampleData(buffer,0)
                    if(size<0)break
                    val time=extractor!!.sampleTime
                    if(time<0 || time>=durationUs)break
                    audioInfo.set(0,size,time,extractor!!.sampleFlags)
                    buffer.position(0); buffer.limit(size)
                    muxer.writeSampleData(audioTrack,buffer,audioInfo)
                    extractor!!.advance()
                }
            }
            surface.release()
        } finally {
            try { encoder.stop() } catch(_:Exception) {}
            encoder.release()
            extractor?.release()
            if(started) try { muxer.stop() } catch(_:Exception) {}
            muxer.release()
        }
    }
}
