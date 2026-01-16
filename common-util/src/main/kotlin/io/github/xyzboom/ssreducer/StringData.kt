package io.github.xyzboom.ssreducer

import java.io.File

@JvmInline
value class StringData(val data: String) : ISavable {
    override fun saveTo(file: File) {
        file.writeText(data)
    }
}