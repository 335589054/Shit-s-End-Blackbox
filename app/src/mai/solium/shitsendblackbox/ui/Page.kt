package mai.solium.shitsendblackbox.ui

import android.content.Intent
import android.view.View

interface Page {
    fun view(): View
    fun refresh()

    /** 页面可选接收 Activity 结果（例如 VPN 授权）。 */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {}
}
