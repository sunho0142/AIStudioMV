package com.example.aistudiomv

import android.app.*
import android.os.*
import android.content.*
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import android.view.*
import android.widget.*
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val photos = mutableListOf<Uri>()
    private var music: Uri? = null
    private lateinit var status: TextView
    private lateinit var prompt: EditText
    private val worker = Executors.newSingleThreadExecutor()
    private val pickPhotos = 101
    private val pickMusic = 102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(17, 18, 28)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 30, 32, 20); setBackgroundColor(Color.rgb(17,18,28)) }
        val title = TextView(this).apply { text="AI Studio MV"; textSize=26f; setTextColor(Color.WHITE) }
        root.addView(title)
        root.addView(label("사진 · 영상 · 뮤직비디오 제작", 15f))
        prompt = EditText(this).apply { hint="만들고 싶은 사진이나 영상 설명"; setHintTextColor(Color.LTGRAY); setTextColor(Color.WHITE); minLines=2 }
        root.addView(prompt)
        addButton(root,"AI 사진 생성 (연결 안내)") { showApiHelp("사진") }
        addButton(root,"AI 영상 생성 (연결 안내)") { showApiHelp("영상") }
        addButton(root,"사진 여러 장 선택") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="image/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true); addCategory(Intent.CATEGORY_OPENABLE) }, pickPhotos)
        }
        addButton(root,"음악 선택 (AAC/M4A 권장)") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="audio/*"; addCategory(Intent.CATEGORY_OPENABLE) }, pickMusic)
        }
        addButton(root,"MP4 뮤직비디오 만들기") { createVideo() }
        status=label("사진을 선택하면 1280×720 MP4를 제작할 수 있어요.",14f)
        root.addView(status)
        val scroll=ScrollView(this); scroll.addView(root); setContentView(scroll)
    }
    private fun label(t:String, s:Float)=TextView(this).apply { text=t; textSize=s; setTextColor(Color.rgb(215,215,230)); setPadding(0,12,0,12) }
    private fun addButton(parent:LinearLayout,t:String,action:()->Unit) { parent.addView(Button(this).apply { text=t; setOnClickListener { action() } }) }
    private fun showApiHelp(type:String) = AlertDialog.Builder(this).setTitle("AI $type 생성").setMessage("이 버튼은 서버 연동 전 안내 기능입니다. 실제 생성은 별도 AI 서비스의 API와 보안 서버 연결이 필요합니다. API 키를 앱 코드에 넣지 마세요.").setPositiveButton("확인",null).show()
    @Deprecated("Uses classic document picker callback for broad compatibility")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK || data==null)return
        if(requestCode==pickPhotos){
            photos.clear()
            val clip=data.clipData
            if(clip!=null) for(i in 0 until clip.itemCount) photos.add(clip.getItemAt(i).uri)
            else data.data?.let { photos.add(it) }
            status.text="사진 ${photos.size}장 선택됨"
        } else if(requestCode==pickMusic){ music=data.data; status.text="음악 선택됨: ${music?.lastPathSegment}" }
    }
    private fun createVideo(){
        if(photos.isEmpty()){ Toast.makeText(this,"사진을 먼저 선택하세요",Toast.LENGTH_SHORT).show(); return }
        val selected=photos.toList(); val audio=music
        status.text="영상 제작 중… 앱을 닫지 마세요."
        worker.execute {
            try {
                val name="AIStudioMV_${System.currentTimeMillis()}.mp4"
                val values=android.content.ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME,name); put(MediaStore.Video.Media.MIME_TYPE,"video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/AIStudioMV")
                    put(MediaStore.Video.Media.IS_PENDING,1)
                }
                val uri=contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values) ?: error("저장 공간을 열 수 없습니다")
                try {
                    contentResolver.openFileDescriptor(uri,"w")!!.use { descriptor ->
                        VideoMaker(this).make(selected,audio,descriptor.fileDescriptor)
                    }
                    values.clear(); values.put(MediaStore.Video.Media.IS_PENDING,0)
                    contentResolver.update(uri,values,null,null)
                    runOnUiThread { status.text="완료! 갤러리의 Movies/AIStudioMV에서 확인하세요.\n$uri" }
                } catch(e:Exception) { contentResolver.delete(uri,null,null); throw e }
            } catch(e:Exception) { runOnUiThread { status.text="제작 실패: ${e.message}" } }
        }
    }
    override fun onDestroy(){ super.onDestroy(); worker.shutdown() }
}
