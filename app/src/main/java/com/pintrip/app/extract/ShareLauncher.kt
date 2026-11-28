package com.pintrip.app.extract

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Patterns

/**
 * 앱 안에서 장소 추가를 시작할 때 16 공유 수신 화면을 여는 헬퍼.
 *
 * 다른 앱에서 PinTrip으로 공유할 때와 똑같은 형식(ACTION_SEND / ACTION_SEND_MULTIPLE)으로 만들어서
 * ShareReceiveActivity가 기존 ShareIntentParser로 그대로 처리하게 한다.
 *
 * 쓰는 곳: 06 내 여행방 링크바·사진 버튼, 15 장소 추가 시트.
 * 15에서 들어오면 [preselectedRoomId]로 그 방이 미리 선택되고, 06에서는 16 화면에서 고른다
 * (방이 1개면 자동 선택).
 */
object ShareLauncher {

    /** ShareIntentParser가 처리하는 최대 스크린샷 수와 맞춘다. */
    const val MAX_IMAGES = 5

    /**
     * 공유할 텍스트 안에 링크가 있는지 확인한다.
     * 링크가 없으면 Gemini 호출까지 간 뒤에야 실패하므로 호출 전에 걸러낸다.
     */
    fun hasUrl(text: String): Boolean = Patterns.WEB_URL.matcher(text).find()

    fun openLink(context: Context, text: String, preselectedRoomId: String? = null) {
        val intent = ShareReceiveActivity.newIntent(context, preselectedRoomId).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(intent)
    }

    fun openImages(context: Context, uris: List<Uri>, preselectedRoomId: String? = null) {
        if (uris.isEmpty()) return
        val intent = ShareReceiveActivity.newIntent(context, preselectedRoomId).apply {
            type = "image/*"
            if (uris.size == 1) {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, uris.first())
            } else {
                action = Intent.ACTION_SEND_MULTIPLE
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            // 포토피커가 준 읽기 권한을 다음 화면에도 넘긴다
            clipData = ClipData.newRawUri(null, uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
