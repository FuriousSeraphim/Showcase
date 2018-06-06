package io.seraphim.headsandhands.example

import com.google.gson.Gson
import com.google.gson.stream.JsonReader

class City(
        @JvmField val name: String,
        @JvmField val temperature: Temperature,
        @JvmField val weather: Weather
) {
    class TypeAdapter(private val gson: Gson) : GsonTypeAdapter<City>() {
        override fun read(reader: JsonReader): City {
            var name = ""
            var temperature: Temperature? = null
            var weather: Weather? = null

            reader.readObject {
                when (it) {
                    "weather" -> weather = gson.fromArray<Weather>(reader).firstOrNull()
                    "main" -> temperature = gson.fromJson(reader)
                    "name" -> name = reader.nextString()
                    else -> reader.skipValue()
                }
            }

            return City(
                    name,
                    temperature ?: throw IllegalArgumentException("temperature can't be null"),
                    weather ?: throw IllegalArgumentException("weather can't be null")
            )
        }
    }
}

class Temperature(
        @JvmField val value: Float,
        @JvmField val min: Float,
        @JvmField val max: Float
) {
    class TypeAdapter : GsonTypeAdapter<Temperature>() {
        override fun read(reader: JsonReader): Temperature {
            var value = Float.MIN_VALUE
            var min = Float.MIN_VALUE
            var max = Float.MIN_VALUE

            reader.readObject { name ->
                when (name) {
                    "temp" -> value = reader.nextDouble().toFloat()
                    "temp_min" -> min = reader.nextDouble().toFloat()
                    "temp_max" -> max = reader.nextDouble().toFloat()
                    else -> reader.skipValue()
                }
            }

            return Temperature(value, min, max)
        }
    }
}

class Weather(
        @JvmField val description: String,
        @JvmField val icon: String
) {
    class TypeAdapter : GsonTypeAdapter<Weather>() {
        override fun read(reader: JsonReader): Weather {
            var description = ""
            var icon = ""

            reader.readObject { name ->
                when (name) {
                    "description" -> description = reader.nextString()
                    "icon" -> icon = "http://openweathermap.org/img/w/${reader.nextString()}.png"
                    else -> reader.skipValue()
                }
            }

            return Weather(description, icon)
        }
    }
}