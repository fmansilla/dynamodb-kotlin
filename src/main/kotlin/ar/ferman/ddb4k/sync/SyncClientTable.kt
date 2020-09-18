package ar.ferman.ddb4k.sync

import ar.ferman.ddb4k.Table
import ar.ferman.ddb4k.TableDefinition
import ar.ferman.ddb4k.builder.Query
import ar.ferman.ddb4k.builder.Scan
import ar.ferman.ddb4k.builder.TableSupport
import ar.ferman.ddb4k.builder.Update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue

class SyncClientTable<T : Any>(
    private val dynamoDbClient: DynamoDbClient,
    private val tableDefinition: TableDefinition<T>
) : Table<T> {
    private val tableSupport = TableSupport(tableDefinition)

    override fun query(block: Query<T>.() -> Unit): Flow<T> {
        val queryBuilder = Query(tableDefinition)

        block.invoke(queryBuilder)

        return flow {
            var lastEvaluatedKey = emptyMap<String, AttributeValue>()

            do {
                val queryRequest = queryBuilder.build(lastEvaluatedKey)
                lateinit var pageContent: List<T>

                withContext(Dispatchers.IO) {
                    val result = dynamoDbClient.query(queryRequest)
                    pageContent = (result?.items()?.mapNotNull { queryBuilder.mapper.invoke(it) } ?: emptyList())
                    lastEvaluatedKey = result?.lastEvaluatedKey() ?: emptyMap()
                }

                pageContent.forEach { emit(it) }
            } while (lastEvaluatedKey.isNotEmpty())
        }
    }

    override suspend fun put(value: T) = withContext(Dispatchers.IO) {
        val putItemRequest = tableSupport.buildPutItemRequest(value)

        dynamoDbClient.putItem(putItemRequest)

        Unit
    }

    override fun scan(block: Scan<T>.() -> Unit): Flow<T> {
        val scanBuilder = Scan<T>(tableDefinition)

        block.invoke(scanBuilder)

        return flow {
            var lastEvaluatedKey = emptyMap<String, AttributeValue>()

            do {
                val queryRequest = scanBuilder.build(lastEvaluatedKey)
                lateinit var pageContent: List<T>

                withContext(Dispatchers.IO) {
                    val result = dynamoDbClient.scan(queryRequest)
                    pageContent = (result?.items()?.mapNotNull { scanBuilder.mapper.invoke(it) } ?: emptyList())
                    lastEvaluatedKey = result?.lastEvaluatedKey() ?: emptyMap()
                }

                pageContent.forEach { emit(it) }
            } while (lastEvaluatedKey.isNotEmpty())
        }
    }

    override suspend fun update(update: Update<T>.() -> Unit) {
        val updateBuilder = Update(tableDefinition)
        update(updateBuilder)

        val updateItemRequest = updateBuilder.build()

        withContext(Dispatchers.IO) {
            dynamoDbClient.updateItem(updateItemRequest)
        }
    }

    override suspend fun get(key: T): T? = withContext(Dispatchers.IO) {
        val getItemRequest = tableSupport.buildGetItemRequest(key)

        dynamoDbClient.getItem(getItemRequest).item()
            ?.takeIf { !it.isNullOrEmpty() }
            ?.let(tableDefinition::fromItem)
    }

    override suspend fun get(keys: Set<T>): List<T> = withContext(Dispatchers.IO) {
        val batchGetItemRequest = tableSupport.buildBatchGetItemRequest(keys)

        dynamoDbClient.batchGetItem(batchGetItemRequest).responses()[tableDefinition.tableName]
            ?.mapNotNull {
                it.takeIf { !it.isNullOrEmpty() }?.let(tableDefinition::fromItem)
            }
            ?: emptyList()
    }

    override suspend fun delete(key: T) = withContext(Dispatchers.IO) {
        val deleteItemRequest = tableSupport.buildDeleteItemRequest(key)

        dynamoDbClient.deleteItem(deleteItemRequest)

        Unit
    }
}