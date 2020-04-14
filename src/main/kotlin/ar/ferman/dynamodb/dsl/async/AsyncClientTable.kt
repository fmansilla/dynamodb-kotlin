package ar.ferman.dynamodb.dsl.async

import ar.ferman.dynamodb.dsl.Table
import ar.ferman.dynamodb.dsl.TableDefinition
import ar.ferman.dynamodb.dsl.TableSupport
import ar.ferman.dynamodb.dsl.builder.Query
import ar.ferman.dynamodb.dsl.builder.Scan
import ar.ferman.dynamodb.dsl.builder.Update
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


class AsyncClientTable<T : Any>(
    private val dynamoDbClient: DynamoDbAsyncClient,
    private val tableDefinition: TableDefinition<T>
) : Table<T> {

    private val tableSupport = TableSupport(tableDefinition)

    override suspend fun create(customize: CreateTableRequest.Builder.() -> Unit) {
        val createTableRequest = tableSupport.buildCreateTableRequest(customize)

        return suspendCoroutine { continuation ->
            dynamoDbClient.createTable(createTableRequest).whenComplete { _, error ->
                if (error != null) {
                    continuation.resumeWithException(error)
                } else {
                    continuation.resume(Unit)
                }
            }
        }
    }

    override suspend fun delete() {
        val deleteTableRequest = tableSupport.buildDeleteTableRequest()

        return suspendCoroutine { continuation ->
            dynamoDbClient.deleteTable(deleteTableRequest).whenComplete { _, error ->
                if (error != null) {
                    continuation.resumeWithException(error)
                } else {
                    continuation.resume(Unit)
                }
            }
        }
    }

    override fun query(block: Query<T>.() -> Unit): Flow<T> {
        val queryBuilder = Query(tableDefinition)

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

    override suspend fun put(value: T) {
        val putItemRequest = tableSupport.buildPutItemRequest(value)

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

    override fun scan(block: Scan<T>.() -> Unit): Flow<T> {
        val scanBuilder = Scan(tableDefinition)

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


    override suspend fun update(update: Update<T>.() -> Unit) {
        val updateBuilder = Update(tableDefinition)
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

    override suspend fun get(key: T): T? {
        val getItemRequest = tableSupport.buildGetItemRequest(key)

        return suspendCoroutine { continuation ->
            dynamoDbClient.getItem(getItemRequest).whenComplete { response, error ->
                if (error != null) {
                    continuation.resumeWithException(error)
                } else {
                    continuation.resume(response.item()?.takeIf { !it.isNullOrEmpty() }?.let(tableDefinition::fromItem))
                }
            }
        }
    }

    override suspend fun get(keys: Set<T>): List<T> {
        val batchGetItemRequest = tableSupport.buildBatchGetItemRequest(keys)

        return suspendCoroutine { continuation ->
            dynamoDbClient.batchGetItem(batchGetItemRequest).whenComplete { response, error ->
                if (error != null) {
                    continuation.resumeWithException(error)
                } else {
                    continuation.resume(response.responses()[tableDefinition.tableName]
                        ?.mapNotNull {
                            it.takeIf { !it.isNullOrEmpty() }?.let(tableDefinition::fromItem)
                        }
                        ?: emptyList())
                }
            }
        }
    }
}