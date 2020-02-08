package ar.ferman.dynamodb.dsl.builder

import ar.ferman.dynamodb.dsl.Attributes
import ar.ferman.dynamodb.dsl.toAttributeValue
import software.amazon.awssdk.services.dynamodb.model.AttributeValue

class KeyCondition {

    private val attributes = mutableMapOf<String, AttributeValue>()

    infix fun String.eq(value: String) {attributes[this] = value.toAttributeValue()}
    infix fun String.eq(value: Number) {attributes[this] = value.toAttributeValue()}

    fun build(): Attributes {
        return attributes
    }
}