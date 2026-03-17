package app.aaps.wear.medtrum.util

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import android.util.TypedValue
import androidx.core.content.ContextCompat
import app.aaps.core.interfaces.resources.ResourceHelper

class WearResourceHelper(private val context: Context) : ResourceHelper {

    override fun gs(id: Int): String =
        try { context.getString(id) } catch (_: Exception) { "[$id]" }

    override fun gs(id: Int, vararg args: Any?): String =
        try { context.getString(id, *args) } catch (_: Exception) { "[$id]" }

    override fun gq(id: Int, quantity: Int, vararg args: Any?): String =
        try { context.resources.getQuantityString(id, quantity, *args) } catch (_: Exception) { "[$id]" }

    override fun gsNotLocalised(id: Int, vararg args: Any?): String =
        try { context.getString(id, *args) } catch (_: Exception) { "[$id]" }

    override fun gc(id: Int): Int =
        try { ContextCompat.getColor(context, id) } catch (_: Exception) { 0 }

    override fun gd(id: Int): Drawable? =
        try { ContextCompat.getDrawable(context, id) } catch (_: Exception) { null }

    override fun gb(id: Int): Boolean =
        try { context.resources.getBoolean(id) } catch (_: Exception) { false }

    override fun gcs(id: Int): String =
        try {
            val color = ContextCompat.getColor(context, id)
            String.format("#%06X", 0xFFFFFF and color)
        } catch (_: Exception) { "#000000" }

    override fun gsa(id: Int): Array<String> =
        try { context.resources.getStringArray(id) } catch (_: Exception) { emptyArray() }

    override fun openRawResourceFd(id: Int): AssetFileDescriptor? =
        try { context.resources.openRawResourceFd(id) } catch (_: Exception) { null }

    override fun decodeResource(id: Int): Bitmap =
        BitmapFactory.decodeResource(context.resources, id)

    override fun getDisplayMetrics(): DisplayMetrics =
        context.resources.displayMetrics

    override fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density + 0.5f).toInt()

    override fun dpToPx(dp: Float): Int =
        (dp * context.resources.displayMetrics.density + 0.5f).toInt()

    override fun shortTextMode(): Boolean = false

    override fun gac(attributeId: Int): Int {
        val typedValue = TypedValue()
        return if (context.theme.resolveAttribute(attributeId, typedValue, true)) {
            typedValue.data
        } else 0
    }

    override fun gac(context: Context?, attributeId: Int): Int {
        val ctx = context ?: this.context
        val typedValue = TypedValue()
        return if (ctx.theme.resolveAttribute(attributeId, typedValue, true)) {
            typedValue.data
        } else 0
    }

    override fun getThemedCtx(context: Context): Context = context
}
