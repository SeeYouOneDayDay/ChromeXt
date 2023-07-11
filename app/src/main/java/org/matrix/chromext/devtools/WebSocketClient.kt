package org.matrix.chromext.devtools

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.util.Base64
import java.io.OutputStream
import java.security.SecureRandom
import kotlin.experimental.xor
import org.json.JSONObject
import org.matrix.chromext.hook.UserScriptHook
import org.matrix.chromext.hook.WebViewHook
import org.matrix.chromext.utils.Log

class DevToolClient(tabId: String) : LocalSocket() {

  val tabId = tabId
  var id = 1

  init {
    connectDevTools(this)
    val alphabet: List<Char> = ('a'..'z') + ('A'..'Z') + ('0'..'9')
    val randomString = List(16) { alphabet.random() }.joinToString("")
    val request =
        arrayOf(
            "GET /devtools/page/${tabId} HTTP/1.1",
            "Connection: Upgrade",
            "Upgrade: websocket",
            "Sec-WebSocket-Version: 13",
            "Sec-WebSocket-Key: ${Base64.encodeToString(randomString.toByteArray(), Base64.DEFAULT).trim()}")
    Log.d("Connecting to " + request[0])
    outputStream.write((request.joinToString("\r\n") + "\r\n\r\n").toByteArray())
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE / 8)
    inputStream.read(buffer)
    // Log.d(String(buffer))
  }

  fun command(id: Int, method: String, params: JSONObject) {
    WebSocketFrame(JSONObject(mapOf("id" to id, "method" to method, "params" to params)).toString())
        .write(outputStream)
  }

  fun evaluateJavascript(script: String?): Boolean {
    if (script != null) {
      runCatching {
            command(this.id, "Runtime.evaluate", JSONObject(mapOf("expression" to script)))
            this.id += 1
          }
          .onFailure {
            Log.ex(it)
            close()
            return false
          }
    }
    return true
  }

  fun listen(callback: (JSONObject) -> Unit = { msg -> Log.d(msg.toString()) }) {
    runCatching {
          while (true) {
            val type = inputStream.read()
            if (type == 0x80 or 0x1) {
              var len = inputStream.read()
              if (len == 0x7e) {
                len = inputStream.read() shl 8
                len += inputStream.read()
              } else if (len == 0x7f) {
                len = 0
                for (i in 0 until 8) {
                  len = len or (inputStream.read() shl (8 * (7 - i)))
                }
              } else if (len > 0x7d) {
                throw Exception("Payload from server has invalid length byte ${len}")
              }
              callback(JSONObject(String(inputStream.readNBytes(len))))
            } else {
              throw Exception("Invalid frame type ${type} received from devtools server")
            }
          }
        }
        .onFailure {
          Log.e("Fails when listening at tab ${tabId}: ${it.message}")
          close()
        }
  }
}

class WebSocketFrame(msg: String?) {
  private val mFin: Int
  private val mRsv1: Int
  private val mRsv2: Int
  private val mRsv3: Int
  private val mOpcode: Int
  private val mPayload: ByteArray

  var mMask: Boolean = false

  init {
    mFin = 0x80
    mRsv1 = 0x00
    mRsv2 = 0x00
    mRsv3 = 0x00
    mOpcode = 0x1
    mPayload =
        if (msg == null) {
          ByteArray(0)
        } else {
          msg.toByteArray()
        }
  }

  fun write(os: OutputStream) {
    writeFrame0(os)
    writeFrame1(os)
    writeFrameExtendedPayloadLength(os)
    val maskingKey = ByteArray(4)
    SecureRandom().nextBytes(maskingKey)
    os.write(maskingKey)
    writeFramePayload(os, maskingKey)
  }

  private fun writeFrame0(os: OutputStream) {
    val b = mFin or mRsv1 or mRsv2 or mRsv1 or (mOpcode and 0x0F)
    os.write(b)
  }

  private fun writeFrame1(os: OutputStream) {
    var b = 0x80
    val len = mPayload.size
    if (len <= 0x7d) {
      b = b or len
    } else if (len <= 0xffff) {
      b = b or 0x7e
    } else {
      b = b or 0x7f
    }
    os.write(b)
  }

  private fun writeFrameExtendedPayloadLength(os: OutputStream) {
    var len = mPayload.size
    val buf: ByteArray
    if (len <= 0x7d) {
      return
    } else if (len <= 0xffff) {
      buf = ByteArray(2)
      buf[1] = (len and 0xff).toByte()
      buf[0] = ((len shr 8) and 0xff).toByte()
    } else {
      buf = ByteArray(8)
      for (i in 0 until 8) {
        buf[7 - i] = (len and 0xff).toByte()
        len = len shr 8
      }
    }
    os.write(buf)
  }

  private fun writeFramePayload(os: OutputStream, mask: ByteArray) {
    os.write(mPayload.mapIndexed { index, byte -> byte xor mask[index.rem(4)] }.toByteArray())
  }
}

fun connectDevTools(client: LocalSocket) {
  val address =
      if (UserScriptHook.isInit) {
        "chrome_devtools_remote"
      } else if (WebViewHook.isInit) {
        "webview_devtools_remote"
      } else {
        throw Exception("DevTools started unexpectedly")
      }

  runCatching { client.connect(LocalSocketAddress(address)) }
      .onFailure { client.connect(LocalSocketAddress(address + "_" + Process.myPid())) }
}
