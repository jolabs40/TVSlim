package net.jolabs40.tvslim.windows.ecran

import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.imageio.ImageIO

/** Puts a screenshot on the Windows clipboard as an image. */
object PressePapiers {

    fun copierImage(png: ByteArray) {
        val image = ImageIO.read(ByteArrayInputStream(png)) ?: throw IOException("image illisible")
        Toolkit.getDefaultToolkit().systemClipboard.setContents(ImageTransferable(image), null)
    }

    private class ImageTransferable(private val image: Image) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor

        override fun getTransferData(flavor: DataFlavor): Any {
            if (flavor != DataFlavor.imageFlavor) throw UnsupportedFlavorException(flavor)
            return image
        }
    }
}
