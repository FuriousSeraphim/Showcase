package io.seraphim.headsandhands.example

import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.util.*

abstract class GsonTypeAdapter<T> : TypeAdapter<T>() {
    override fun write(writer: JsonWriter, model: T) {}
    override fun read(reader: JsonReader): T? = null
}

inline fun <reified T : Any> Gson.fromJson(reader: JsonReader): T = fromJson<T>(reader, T::class.java)

inline fun JsonReader.readArray(func: () -> Unit) {
    beginArray()
    while (hasNext()) func()
    endArray()
}

inline fun JsonReader.readObject(func: (name: String) -> Unit) {
    beginObject()
    while (hasNext()) func(nextName())
    endObject()
}

inline fun <reified T> Gson.fromArray(reader: JsonReader): List<T> {
    val list = ArrayList<T>()
    reader.beginArray()
    while (reader.hasNext()) list.add(fromJson<T>(reader, T::class.java))
    reader.endArray()
    return list
}