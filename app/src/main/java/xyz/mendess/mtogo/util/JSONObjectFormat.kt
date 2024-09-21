package xyz.mendess.mtogo.util

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import org.json.JSONArray
import org.json.JSONObject

inline fun <reified T : Any> encodeToJSONObject(
    t: T,
    serializersModule: SerializersModule = EmptySerializersModule(),
): JSONObject = encodeToJSONObject(t, serializersModule, serializer())

fun <T> encodeToJSONObject(
    t: T,
    serializersModule: SerializersModule = EmptySerializersModule(),
    serializer: SerializationStrategy<T>,
): JSONObject {
    val encoder = JSONObjectEncoder(serializersModule)
    encoder.encodeSerializableValue(serializer, t)
    return encoder.jsonObject
}

private class JSONObjectEncoder : AbstractEncoder {
    constructor(serializersModule: SerializersModule) : this(serializersModule, JSONObject())
    private constructor(serializersModule: SerializersModule, jsonObject: JSONObject) : super() {
        this.serializersModule = serializersModule
        this.jsonObject = jsonObject
    }

    override val serializersModule: SerializersModule

    val jsonObject: JSONObject
    private var currentKey: String? = null

    override fun beginCollection(
        descriptor: SerialDescriptor,
        collectionSize: Int
    ): CompositeEncoder {
        val array = JSONArray()
        jsonObject.put(currentKey!!, array)
        return JSONObjectCompositeEncoder(array, serializersModule)
    }

    @ExperimentalSerializationApi
    override fun <T : Any> encodeNullableSerializableValue(
        serializer: SerializationStrategy<T>,
        value: T?
    ) {
        if (value == null) {
            jsonObject.put(currentKey!!, JSONObject.NULL)
        } else {
            jsonObject.put(currentKey!!, encodeToJSONObject(value, serializersModule, serializer))
        }
    }

    @ExperimentalSerializationApi
    override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
        jsonObject.put(currentKey!!, encodeToJSONObject(value, serializersModule, serializer))
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        val obj = JSONObject()
        jsonObject.put(currentKey!!, obj)
        return JSONObjectEncoder(serializersModule, obj)
    }

    override fun encodeBoolean(value: Boolean) {
        jsonObject.put(currentKey!!, value)
    }

    override fun encodeByte(value: Byte) {
        jsonObject.put(currentKey!!, value)
    }

    override fun encodeChar(value: Char) {
        jsonObject.put(currentKey!!, value)
    }

    override fun encodeDouble(value: Double) {
        jsonObject.put(currentKey!!, value)
    }

    override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
        currentKey = descriptor.getElementName(index)
        return true
    }

    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
        currentKey = enumDescriptor.getElementName(index)
    }

    override fun encodeFloat(value: Float) {
        jsonObject.put(currentKey!!, value)
    }

    override fun encodeInt(value: Int) {
        jsonObject.put(currentKey!!, value)
    }

    override fun encodeLong(value: Long) {
        jsonObject.put(currentKey!!, value)
    }

    override fun encodeNull() {
        jsonObject.put(currentKey!!, JSONObject.NULL)
    }

    override fun encodeShort(value: Short) {
        jsonObject.put(currentKey!!, value)
    }

    override fun encodeString(value: String) {
        jsonObject.put(currentKey!!, value)
    }

    override fun encodeValue(value: Any) {
        jsonObject.put(currentKey!!, value)
    }

    @ExperimentalSerializationApi
    override fun shouldEncodeElementDefault(descriptor: SerialDescriptor, index: Int): Boolean {
        return super.shouldEncodeElementDefault(descriptor, index)
    }

    class JSONObjectCompositeEncoder(
        val array: JSONArray,
        override val serializersModule: SerializersModule
    ) : CompositeEncoder {
        override fun encodeBooleanElement(
            descriptor: SerialDescriptor,
            index: Int,
            value: Boolean
        ) {
            array.put(value)
        }

        override fun encodeByteElement(descriptor: SerialDescriptor, index: Int, value: Byte) {
            array.put(value)
        }

        override fun encodeCharElement(descriptor: SerialDescriptor, index: Int, value: Char) {
            array.put(value)
        }

        override fun encodeDoubleElement(descriptor: SerialDescriptor, index: Int, value: Double) {
            array.put(value)
        }

        override fun encodeFloatElement(descriptor: SerialDescriptor, index: Int, value: Float) {
            array.put(value)
        }

        override fun encodeInlineElement(descriptor: SerialDescriptor, index: Int): Encoder {
            val jsonObj = JSONObject()
            array.put(jsonObj)
            return JSONObjectEncoder(serializersModule, jsonObj)
        }

        override fun encodeIntElement(descriptor: SerialDescriptor, index: Int, value: Int) {
            array.put(value)
        }

        override fun encodeLongElement(descriptor: SerialDescriptor, index: Int, value: Long) {
            array.put(value)
        }

        @ExperimentalSerializationApi
        override fun <T : Any> encodeNullableSerializableElement(
            descriptor: SerialDescriptor,
            index: Int,
            serializer: SerializationStrategy<T>,
            value: T?
        ) {
            val obj = JSONObjectEncoder(serializersModule)
                .also { encodeNullableSerializableElement(descriptor, index, serializer, value) }
                .jsonObject
            array.put(obj)
        }

        override fun <T> encodeSerializableElement(
            descriptor: SerialDescriptor,
            index: Int,
            serializer: SerializationStrategy<T>,
            value: T
        ) {
            val obj = JSONObjectEncoder(serializersModule)
                .also { encodeSerializableElement(descriptor, index, serializer, value) }
                .jsonObject
            array.put(obj)
        }

        override fun encodeShortElement(descriptor: SerialDescriptor, index: Int, value: Short) {
            array.put(value)
        }

        override fun encodeStringElement(descriptor: SerialDescriptor, index: Int, value: String) {
            array.put(value)
        }

        override fun endStructure(descriptor: SerialDescriptor) { }
    }
}
