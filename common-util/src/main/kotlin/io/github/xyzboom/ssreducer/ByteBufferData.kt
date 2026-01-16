package io.github.xyzboom.ssreducer

import com.intellij.util.io.toByteArray
import java.io.File
import java.nio.ByteBuffer

@JvmInline
value class ByteBufferData(val data: ByteBuffer) : ISavable {
    override fun saveTo(file: File) {
        file.writeBytes(data.toByteArray())
    }

    constructor(data: ByteArray) : this(ByteBuffer.wrap(data))
}