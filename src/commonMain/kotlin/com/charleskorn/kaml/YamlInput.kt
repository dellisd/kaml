/*

   Copyright 2018-2021 Charles Korn.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

*/

package com.charleskorn.kaml

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.modules.SerializersModule

@OptIn(ExperimentalSerializationApi::class)
public sealed class YamlInput(
    public val node: YamlNode,
    override var serializersModule: SerializersModule,
    public val configuration: YamlConfiguration
) : AbstractDecoder() {
    internal companion object {
        private val missingFieldExceptionMessage: Regex = """^Field '(.*)' is required for type with serial name '.*', but it was missing$""".toRegex()

        internal fun createFor(node: YamlNode, context: SerializersModule, configuration: YamlConfiguration, descriptor: SerialDescriptor): YamlInput = when (node) {
            is YamlNull -> when {
                descriptor.kind is PolymorphicKind && !descriptor.isNullable -> throw MissingTypeTagException(node.path)
                else -> YamlNullInput(node, context, configuration)
            }

            is YamlScalar -> when (descriptor.kind) {
                is PrimitiveKind, SerialKind.ENUM -> YamlScalarInput(node, context, configuration)
                is SerialKind.CONTEXTUAL -> YamlContextualInput(node, context, configuration)
                is PolymorphicKind -> throw MissingTypeTagException(node.path)
                else -> throw IncorrectTypeException("Expected ${descriptor.kind.friendlyDescription}, but got a scalar value", node.path)
            }

            is YamlList -> when (descriptor.kind) {
                is StructureKind.LIST -> YamlListInput(node, context, configuration)
                is SerialKind.CONTEXTUAL -> YamlContextualInput(node, context, configuration)
                else -> throw IncorrectTypeException("Expected ${descriptor.kind.friendlyDescription}, but got a list", node.path)
            }

            is YamlMap -> when (descriptor.kind) {
                is StructureKind.CLASS, StructureKind.OBJECT -> YamlObjectInput(node, context, configuration)
                is StructureKind.MAP -> YamlMapInput(node, context, configuration)
                is SerialKind.CONTEXTUAL -> YamlContextualInput(node, context, configuration)
                is PolymorphicKind -> when (configuration.polymorphismStyle) {
                    PolymorphismStyle.Tag -> throw MissingTypeTagException(node.path)
                    PolymorphismStyle.Property -> createPolymorphicMapDeserializer(node, context, configuration)
                }
                else -> throw IncorrectTypeException("Expected ${descriptor.kind.friendlyDescription}, but got a map", node.path)
            }

            is YamlTaggedNode -> when {
                descriptor.kind is PolymorphicKind && configuration.polymorphismStyle == PolymorphismStyle.Tag -> YamlPolymorphicInput(node.tag, node.path, node.innerNode, context, configuration)
                else -> createFor(node.innerNode, context, configuration, descriptor)
            }
        }

        private fun createPolymorphicMapDeserializer(node: YamlMap, context: SerializersModule, configuration: YamlConfiguration): YamlPolymorphicInput {
            val desiredKey = configuration.polymorphismPropertyName
            when (val typeName = node.getValue(desiredKey)) {
                is YamlList -> throw InvalidPropertyValueException(desiredKey, "expected a string, but got a list", typeName.path)
                is YamlMap -> throw InvalidPropertyValueException(desiredKey, "expected a string, but got a map", typeName.path)
                is YamlNull -> throw InvalidPropertyValueException(desiredKey, "expected a string, but got a null value", typeName.path)
                is YamlTaggedNode -> throw InvalidPropertyValueException(desiredKey, "expected a string, but got a tagged value", typeName.path)
                is YamlScalar -> {
                    val remainingProperties = node.withoutKey(desiredKey)

                    return YamlPolymorphicInput(typeName.content, typeName.path, remainingProperties, context, configuration)
                }
            }
        }

        private fun YamlMap.getValue(desiredKey: String): YamlNode {
            return this.get(desiredKey) ?: throw MissingRequiredPropertyException(desiredKey, this.path)
        }

        private fun YamlMap.withoutKey(key: String): YamlMap {
            return this.copy(entries = entries.filterKeys { it.content != key })
        }
    }

    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
        try {
            return super.decodeSerializableValue(deserializer)
        } catch (e: SerializationException) {
            throwIfMissingRequiredPropertyException(e)

            throw e
        }
    }

    private fun throwIfMissingRequiredPropertyException(e: SerializationException) {
        val match = missingFieldExceptionMessage.matchEntire(e.message!!) ?: return

        throw MissingRequiredPropertyException(match.groupValues[1], node.path, e)
    }

    public abstract fun getCurrentLocation(): Location
    public abstract fun getCurrentPath(): YamlPath
}

@OptIn(ExperimentalSerializationApi::class)
private val SerialKind.friendlyDescription: String
    get() {
        return when (this) {
            is StructureKind.MAP -> "a map"
            is StructureKind.CLASS -> "an object"
            is StructureKind.OBJECT -> "an object"
            is StructureKind.LIST -> "a list"
            is PrimitiveKind.STRING -> "a string"
            is PrimitiveKind.BOOLEAN -> "a boolean"
            is PrimitiveKind.BYTE -> "a byte"
            is PrimitiveKind.CHAR -> "a character"
            is PrimitiveKind.DOUBLE -> "a double"
            is PrimitiveKind.FLOAT -> "a float"
            is PrimitiveKind.INT -> "an integer"
            is PrimitiveKind.SHORT -> "a short"
            is PrimitiveKind.LONG -> "a long"
            is SerialKind.ENUM -> "an enumeration value"
            else -> "a $this value"
        }
    }
