package ar.ferman.dynamodb.dsl.async

import ar.ferman.dynamodb.dsl.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class Table(
    private val dynamoDbClient: DynamoDbAsyncClient,
    private val tableDefinition: TableDefinition
) {

    suspend fun <T : Any> queryPaginated(block: Query<T>.() -> Unit): PaginatedResult<T> {
        val queryBuilder = Query<T>(tableDefinition)

        block.invoke(queryBuilder)

        return object : PaginatedResult<T>() {
            override suspend fun requestNextPage(lastEvaluatedKey: Attributes): Pair<List<T>, Attributes> =
                suspendCoroutine { continuation ->
                    val queryRequest = queryBuilder.build(lastEvaluatedKey)

                    dynamoDbClient.query(queryRequest).whenComplete { result, error ->
                        if (error != null) {
                            continuation.resumeWithException(error)
                        } else {
                            val pageContent =
                                (result?.items()?.mapNotNull { queryBuilder.mapper.invoke(it) } ?: emptyList())
                            val currentLastEvaluatedKey = result?.lastEvaluatedKey() ?: emptyMap()
                            continuation.resume(pageContent to currentLastEvaluatedKey)
                        }
                    }
                }
        }
    }

    //COLD STREAM using Flow
    //Nothing gets executed or emitted until it is being requested for by a consumer
    fun <T : Any> query(block: Query<T>.() -> Unit): Flow<T> {
        val queryBuilder = Query<T>(tableDefinition)

        block.invoke(queryBuilder)

        return flow {
            var lastEvaluatedKey = emptyMap<String, AttributeValue>()

            do {
                val queryRequest = queryBuilder.build(lastEvaluatedKey)
                lateinit var pageContent: List<T>

                suspendCoroutine<Pair<List<T>, Map<String, AttributeValue>>> { continuation ->
                    dynamoDbClient.query(queryRequest).whenComplete { result, error ->
                        if (error != null) {
                            continuation.resumeWithException(error)
                        } else {
                            pageContent =
                                (result?.items()?.mapNotNull { queryBuilder.mapper.invoke(it) } ?: emptyList())
                            lastEvaluatedKey = result?.lastEvaluatedKey() ?: emptyMap()
                            continuation.resume(pageContent to lastEvaluatedKey)
                        }
                    }
                }

                pageContent.forEach { emit(it) }
            } while (lastEvaluatedKey.isNotEmpty())
        }
    }

    //HOT STREAM using producer
    //The code within the producer is invoked and items are emitted with or without the presence of a consumer.
    //Observable and Flowable types in RxJava are an example of a structure that represents a cold stream of items
//    @ExperimentalCoroutinesApi
//    suspend fun <T : Any> query(block: Query<T>.() -> Unit): ReceiveChannel<T> {
//        val queryBuilder = Query<T>(tableDefinition)
//
//        block.invoke(queryBuilder)
//
//        return CoroutineScope(coroutineContext).produce {
//            var lastEvaluatedKey = emptyMap<String, AttributeValue>()
//
//            do {
//                val queryRequest = queryBuilder.build(lastEvaluatedKey)
//                lateinit var pageContent: List<T>
//
//                suspendCoroutine<Pair<List<T>, Map<String, AttributeValue>>> { continuation ->
//                    dynamoDbClient.query(queryRequest).whenComplete { result, error ->
//                        if (error != null) {
//                            continuation.resumeWithException(error)
//                        } else {
//                            pageContent = (result?.items()?.mapNotNull { queryBuilder.mapper.invoke(it) } ?: emptyList())
//                            lastEvaluatedKey = result?.lastEvaluatedKey() ?: emptyMap()
//                            continuation.resume(pageContent to lastEvaluatedKey)
//                        }
//                    }
//                }
//
//                pageContent.forEach { send(it) }
//            } while (lastEvaluatedKey.isNotEmpty())
//        }
//    }

    suspend fun <T : Any> put(value: T, toItem: (T) -> Attributes) {
        val putItemRequest = PutItemRequest.builder().tableName(tableDefinition.name).item(toItem(value)).build()

        return suspendCoroutine { continuation ->
            dynamoDbClient.putItem(putItemRequest).whenComplete { _, error ->
                if (error != null) {
                    continuation.resumeWithException(error)
                } else {
                    continuation.resume(Unit)
                }
            }
        }
    }

    fun <T : Any> scanPaginated(block: Scan<T>.() -> Unit): PaginatedResult<T> {
        val scanBuilder = Scan<T>(tableDefinition)

        block.invoke(scanBuilder)

        return object : PaginatedResult<T>() {
            override suspend fun requestNextPage(lastEvaluatedKey: Attributes): Pair<List<T>, Attributes> =
                suspendCoroutine { continuation ->
                    val queryRequest = scanBuilder.build(lastEvaluatedKey)

                    dynamoDbClient.scan(queryRequest).whenComplete { result, error ->
                        if (error != null) {
                            continuation.resumeWithException(error)
                        } else {
                            val pageContent =
                                (result?.items()?.mapNotNull { scanBuilder.mapper.invoke(it) } ?: emptyList())
                            val currentLastEvaluatedKey = result?.lastEvaluatedKey() ?: emptyMap()
                            continuation.resume(pageContent to currentLastEvaluatedKey)
                        }
                    }
                }
        }
    }

//    @ExperimentalCoroutinesApi
//    suspend fun <T : Any> scan(block: Scan<T>.() -> Unit): ReceiveChannel<T> {
//        val scanBuilder = Scan<T>(tableDefinition)
//
//        block.invoke(scanBuilder)
//
//        return CoroutineScope(coroutineContext).produce {
//            var lastEvaluatedKey = emptyMap<String, AttributeValue>()
//
//            do {
//                val queryRequest = scanBuilder.build(lastEvaluatedKey)
//                lateinit var pageContent: List<T>
//
//                withContext(Dispatchers.IO) {
//                    val result = dynamoDbClient.scan(queryRequest).get()
//                    pageContent = (result?.items()?.mapNotNull { scanBuilder.mapper.invoke(it) } ?: emptyList())
//                    lastEvaluatedKey = result?.lastEvaluatedKey() ?: emptyMap()
//                }
//
//                pageContent.forEach { send(it) }
//            } while (lastEvaluatedKey.isNotEmpty())
//        }
//    }

    suspend fun <T : Any> scan(block: Scan<T>.() -> Unit): Flow<T> {
        val scanBuilder = Scan<T>(tableDefinition)

        block.invoke(scanBuilder)

        return flow {
            var lastEvaluatedKey = emptyMap<String, AttributeValue>()

            do {
                val queryRequest = scanBuilder.build(lastEvaluatedKey)
                lateinit var pageContent: List<T>

                suspendCoroutine<Unit> { continuation ->
                    dynamoDbClient.scan(queryRequest).whenComplete { result, _ ->
                        pageContent = (result?.items()?.mapNotNull { scanBuilder.mapper.invoke(it) } ?: emptyList())
                        lastEvaluatedKey = result?.lastEvaluatedKey() ?: emptyMap()
                        continuation.resume(Unit)
                    }
                }

                pageContent.forEach { emit(it) }
            } while (lastEvaluatedKey.isNotEmpty())

        }
    }


    suspend fun <T : Any> update(update: Update<T>.() -> Unit) {

        val updateBuilder = Update<T>(tableDefinition)
        update(updateBuilder)

        val updateItemRequest = updateBuilder.build()

        return suspendCoroutine { continuation ->
            dynamoDbClient.updateItem(updateItemRequest).whenComplete { _, error ->
                if (error != null) {
                    continuation.resumeWithException(error)
                } else {
                    continuation.resume(Unit)
                }
            }
        }
    }

    class Update<T>(private val tableDefinition: TableDefinition) {
        private val updateItemRequest = UpdateItemRequest.builder().tableName(tableDefinition.name)

        private val updateExpressions = mutableMapOf<String, MutableList<String>>()
        private val updateExpressionAttributes = mutableMapOf<String, AttributeValue>()

        fun where(block: KeyCondition.() -> Unit) {
            val keyCondition = KeyCondition()
            block.invoke(keyCondition)
            updateItemRequest.key(keyCondition.build())
        }

        fun set(attributeName: String, value: String) {
            getUpdateExpressionsList("SET") += "$attributeName = :$attributeName"
            updateExpressionAttributes[":$attributeName"] = value.toAttributeValue()
        }

        fun set(attributeName: String, value: Number) {
            getUpdateExpressionsList("SET") += "$attributeName = :$attributeName"
            updateExpressionAttributes[":$attributeName"] = value.toAttributeValue()
        }

        fun remove(vararg attributeNames: String) {
            getUpdateExpressionsList("REMOVE").addAll(attributeNames)
        }

//        fun add() {
//            updateExpressions += ""
//        }

        private fun getUpdateExpressionsList(operation: String) =
            updateExpressions.computeIfAbsent(operation) { mutableListOf() }

        fun build(): UpdateItemRequest {
            val updateExpresion = updateExpressions
                .map { (type, expressions) -> "$type ${expressions.joinToString(separator = ", ")}"}
                .joinToString(" ")

            return updateItemRequest
                .expressionAttributeValues(updateExpressionAttributes)
                .updateExpression(updateExpresion)
                .build()
        }
    }

    class KeyCondition {

        private val attributes = mutableMapOf<String, AttributeValue>()

        infix fun String.eq(value: String) {attributes[this] = value.toAttributeValue()}
        infix fun String.eq(value: Number) {attributes[this] = value.toAttributeValue()}

        fun build(): Attributes {
            return attributes
        }
    }
}

