/*
 * Copyright 2021 Realm Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.realm.kotlin.internal

import io.realm.kotlin.UpdatePolicy
import io.realm.kotlin.dynamic.DynamicMutableRealmObject
import io.realm.kotlin.dynamic.DynamicRealmObject
import io.realm.kotlin.internal.dynamic.DynamicUnmanagedRealmObject
import io.realm.kotlin.internal.interop.CollectionType
import io.realm.kotlin.internal.interop.PropertyKey
import io.realm.kotlin.internal.interop.PropertyType
import io.realm.kotlin.internal.interop.RealmCoreException
import io.realm.kotlin.internal.interop.RealmCorePropertyNotNullableException
import io.realm.kotlin.internal.interop.RealmCorePropertyTypeMismatchException
import io.realm.kotlin.internal.interop.RealmInterop
import io.realm.kotlin.internal.interop.RealmListPointer
import io.realm.kotlin.internal.interop.RealmValue
import io.realm.kotlin.internal.platform.realmObjectCompanionOrThrow
import io.realm.kotlin.internal.schema.ClassMetadata
import io.realm.kotlin.internal.schema.PropertyMetadata
import io.realm.kotlin.internal.schema.RealmStorageTypeImpl
import io.realm.kotlin.internal.schema.realmStorageType
import io.realm.kotlin.internal.util.Validation.sdkError
import io.realm.kotlin.schema.RealmStorageType
import io.realm.kotlin.types.BaseRealmObject
import io.realm.kotlin.types.EmbeddedRealmObject
import io.realm.kotlin.types.RealmList
import io.realm.kotlin.types.RealmObject
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1

/**
 * This object holds helper methods for the compiler plugin generated methods, providing the
 * convenience of writing manually code instead of adding it through the compiler plugin.
 *
 * Inlining would anyway yield the same result as generating it.
 */
internal object RealmObjectHelper {
    // Issues (not yet fully uncovered/filed) met when calling these or similar methods from
    // generated code
    // - Generic return type should be R but causes compilation errors for native
    //  e: java.lang.IllegalStateException: Not found Idx for public io.realm.kotlin.internal/RealmObjectHelper|null[0]/
    // - Passing KProperty1<T,R> with inlined reified type parameters to enable fetching type and
    //   property names directly from T/property triggers runtime crash for primitive properties on
    //   Kotlin native. Seems to be an issue with boxing/unboxing

    @Suppress("unused") // Called from generated code
    internal inline fun getValue(
        obj: RealmObjectReference<out BaseRealmObject>,
        propertyName: String,
    ): RealmValue {
        obj.checkValid()
        return getValueByKey(obj, obj.propertyInfoOrThrow(propertyName).key)
    }

    internal inline fun getValueByKey(
        obj: RealmObjectReference<out BaseRealmObject>,
        key: io.realm.kotlin.internal.interop.PropertyKey,
    ): RealmValue = RealmInterop.realm_get_value(obj.objectPointer, key)

    // Return type should be R? but causes compilation errors for native
    @Suppress("unused") // Called from generated code
    internal inline fun <reified R : BaseRealmObject, U> getObject(
        obj: RealmObjectReference<out BaseRealmObject>,
        propertyName: String,
    ): Any? {
        obj.checkValid()
        return realmValueToRealmObject(
            getValue(obj, propertyName),
            R::class,
            obj.mediator,
            obj.owner
        )
    }

    // Return type should be RealmList<R?> but causes compilation errors for native
    @Suppress("unused") // Called from generated code
    internal inline fun <reified R : Any> getList(
        obj: RealmObjectReference<out BaseRealmObject>,
        propertyName: String
    ): ManagedRealmList<Any?> {
        val elementType = R::class
        val realmObjectCompanion = elementType.realmObjectCompanionOrNull()
        val operatorType = if (realmObjectCompanion == null) {
            ListOperatorType.PRIMITIVE
        } else if (!realmObjectCompanion.io_realm_kotlin_isEmbedded) {
            ListOperatorType.REALM_OBJECT
        } else {
            ListOperatorType.EMBEDDED_OBJECT
        }
        return getListByKey(
            obj,
            obj.propertyInfoOrThrow(propertyName).key,
            elementType,
            operatorType
        )
    }

    // Cannot call managedRealmList directly from an inline function
    internal fun <R> getListByKey(
        obj: RealmObjectReference<out BaseRealmObject>,
        key: io.realm.kotlin.internal.interop.PropertyKey,
        elementType: KClass<*>,
        operatorType: ListOperatorType
    ): ManagedRealmList<R> {
        val listPtr = RealmInterop.realm_get_list(obj.objectPointer, key)
        val operator = createListOperator<R>(listPtr, elementType, obj.mediator, obj.owner, operatorType)
        return ManagedRealmList(listPtr, operator)
    }

    internal enum class ListOperatorType {
        PRIMITIVE,
        REALM_OBJECT,
        EMBEDDED_OBJECT
    }

    @Suppress("LongParameterList")
    private fun <R> createListOperator(
        listPtr: RealmListPointer,
        clazz: KClass<*>,
        mediator: Mediator,
        realm: RealmReference,
        operatorType: ListOperatorType
    ): ListOperator<R> {
        val converter: RealmValueConverter<R> =
            converter<Any>(clazz, mediator, realm) as CompositeConverter<R, *>
        return when (operatorType) {
            ListOperatorType.PRIMITIVE -> PrimitiveListOperator(mediator, realm, listPtr, converter)
            ListOperatorType.REALM_OBJECT -> RealmObjectListOperator(
                mediator = mediator,
                realmReference = realm,
                listPtr,
                clazz,
                converter
            )
            ListOperatorType.EMBEDDED_OBJECT -> EmbeddedRealmObjectListOperator(
                mediator,
                realm,
                listPtr,
                clazz,
                converter as RealmValueConverter<EmbeddedRealmObject>
            ) as ListOperator<R>
        }
    }

    @Suppress("unused") // Called from generated code
    internal fun setValue(
        obj: RealmObjectReference<out BaseRealmObject>,
        propertyName: String,
        value: RealmValue
    ) {
        obj.checkValid()
        val key = obj.propertyInfoOrThrow(propertyName).key
        // TODO OPTIMIZE We are currently only doing this check for typed access so could consider
        //  moving the guard into the compiler plugin. Await the implementation of a user
        //  facing general purpose dynamic realm (not only for migration) before doing this, as
        //  this would also require the guard ... or maybe await proper core support for throwing
        //  when this is not supported.
        obj.metadata.let { classMetaData ->
            val primaryKeyPropertyKey: PropertyKey? = classMetaData.primaryKeyProperty?.key
            if (primaryKeyPropertyKey != null && key == primaryKeyPropertyKey) {
                val name = classMetaData[primaryKeyPropertyKey]!!.name
                throw IllegalArgumentException("Cannot update primary key property '${obj.className}.$name'")
            }
        }
        setValueByKey(obj, key, value)
    }

    internal fun setValueByKey(
        obj: RealmObjectReference<out BaseRealmObject>,
        key: io.realm.kotlin.internal.interop.PropertyKey,
        value: RealmValue,
    ) {
        try {
            // TODO Consider making a RealmValue cinterop type and move the various to_realm_value
            //  implementations in the various platform RealmInterops here to eliminate
            //  RealmObjectInterop and make cinterop operate on primitive values and native pointers
            //  only. This relates to the overall concern of having a generic path for getter/setter
            //  instead of generating a typed path for each type.
            RealmInterop.realm_set_value(obj.objectPointer, key, value, false)
            // The catch block should catch specific Core exceptions and rethrow them as Kotlin exceptions.
            // Core exceptions meaning might differ depending on the context, by rethrowing we can add some context related
            // info that might help users to understand the exception.
        } catch (exception: Throwable) {
            throw CoreExceptionConverter.convertToPublicException(exception) { coreException: RealmCoreException ->
                when (coreException) {
                    is RealmCorePropertyNotNullableException -> {
                        IllegalArgumentException("Required property `${obj.className}.${obj.metadata[key]!!.name}` cannot be null")
                    }
                    is RealmCorePropertyTypeMismatchException -> {
                        IllegalArgumentException("Property `${obj.className}.${obj.metadata[key]!!.name}` cannot be assigned with value '${value.value}' of wrong type")
                    }
                    else -> {
                        throw IllegalStateException(
                            "Cannot set `${obj.className}.$${obj.metadata[key]!!.name}` to `${value.value}`: changing Realm data can only be done on a live object from inside a write transaction. Frozen objects can be turned into live using the 'MutableRealm.findLatest(obj)' API.",
                            exception
                        )
                    }
                }
            }
        }
    }

    @Suppress("unused") // Called from generated code
    internal inline fun setObject(
        obj: RealmObjectReference<out BaseRealmObject>,
        propertyName: String,
        value: BaseRealmObject?,
        updatePolicy: UpdatePolicy = UpdatePolicy.ERROR,
        cache: ObjectCache = mutableMapOf()
    ) {
        obj.checkValid()
        val key = obj.propertyInfoOrThrow(propertyName).key
        setObjectByKey(obj, key, value, updatePolicy, cache)
    }

    internal inline fun setObjectByKey(
        obj: RealmObjectReference<out BaseRealmObject>,
        key: io.realm.kotlin.internal.interop.PropertyKey,
        value: BaseRealmObject?,
        updatePolicy: UpdatePolicy = UpdatePolicy.ERROR,
        cache: ObjectCache = mutableMapOf()
    ) {
        setValueByKey(
            obj,
            key,
            realmObjectToRealmValue(value, obj.mediator, obj.owner, updatePolicy, cache)
        )
    }

    internal inline fun setEmbeddedRealmObject(
        obj: RealmObjectReference<out BaseRealmObject>,
        propertyName: String,
        value: BaseRealmObject?,
        updatePolicy: UpdatePolicy = UpdatePolicy.ERROR,
        cache: ObjectCache = mutableMapOf()
    ) {
        obj.checkValid()
        val key = obj.propertyInfoOrThrow(propertyName).key
        setEmbeddedRealmObjectByKey(obj, key, value, updatePolicy, cache)
    }

    internal inline fun setEmbeddedRealmObjectByKey(
        obj: RealmObjectReference<out BaseRealmObject>,
        key: io.realm.kotlin.internal.interop.PropertyKey,
        value: BaseRealmObject?,
        updatePolicy: UpdatePolicy = UpdatePolicy.ERROR,
        cache: ObjectCache = mutableMapOf()
    ) {
        if (value != null) {
            val embedded = RealmInterop.realm_set_embedded(obj.objectPointer, key)
            val newObj = embedded.toRealmObject(value::class, obj.mediator, obj.owner)
            assign(newObj, value, updatePolicy, cache)
        } else {
            setValueByKey(obj, key, RealmValue(null))
        }
    }

    @Suppress("unused") // Called from generated code
    internal inline fun <reified T : Any> setList(
        obj: RealmObjectReference<out BaseRealmObject>,
        col: String,
        list: RealmList<Any?>,
        updatePolicy: UpdatePolicy = UpdatePolicy.ERROR,
        cache: ObjectCache = mutableMapOf()
    ) {
        val existingList = getList<T>(obj, col)
        if (list !is ManagedRealmList || !RealmInterop.realm_equals(
                existingList.nativePointer,
                list.nativePointer
            )
        ) {
            existingList.also {
                it.clear()
                it.operator.insertAll(it.size, list, updatePolicy, cache)
            }
        }
    }

    @Suppress("LongParameterList")
    internal fun assign(
        target: BaseRealmObject,
        source: BaseRealmObject,
        updatePolicy: UpdatePolicy,
        cache: ObjectCache
    ) {
        if (target is DynamicRealmObject) {
            assignDynamic(
                target as DynamicMutableRealmObject,
                source,
                updatePolicy,
                cache
            )
        } else {
            assignTyped(target, source, updatePolicy, cache)
        }
    }

    @Suppress("LongParameterList", "NestedBlockDepth")
    internal fun assignTyped(
        target: BaseRealmObject,
        source: BaseRealmObject,
        updatePolicy: UpdatePolicy,
        cache: ObjectCache
    ) {
        val metadata: ClassMetadata = target.realmObjectReference!!.metadata
        // TODO OPTIMIZE We could set all properties at once with one C-API call
        for (property in metadata.properties) {
            // Primary keys are set at construction time
            if (property.isPrimaryKey) {
                continue
            }

            val name = property.name
            val accessor = property.acccessor ?: sdkError("Typed object should always have an accessor")
            when (property.collectionType) {
                CollectionType.RLM_COLLECTION_TYPE_NONE -> when (property.type) {
                    PropertyType.RLM_PROPERTY_TYPE_OBJECT -> {
                        val isTargetEmbedded = target.realmObjectReference!!.owner.schemaMetadata.getOrThrow(property.linkTarget!!).isEmbeddedRealmObject
                        if (isTargetEmbedded) {
                            setEmbeddedRealmObjectByKey(
                                target.realmObjectReference!!,
                                property.key,
                                accessor.get(source) as EmbeddedRealmObject?,
                                updatePolicy,
                                cache
                            )
                        } else {
                            setObjectByKey(
                                target.realmObjectReference!!,
                                property.key,
                                accessor.get(source) as RealmObject?,
                                updatePolicy,
                                cache
                            )
                        }
                    }
                    else ->
                        accessor.set(target, accessor.get(source))
                }
                CollectionType.RLM_COLLECTION_TYPE_LIST -> {
                    // We cannot use setList as that requires the type, so we need to retrieve the
                    // existing list, wipe it and insert new elements
                    @Suppress("UNCHECKED_CAST")
                    (accessor.get(target) as ManagedRealmList<Any?>).run {
                        clear()
                        operator.insertAll(
                            size,
                            accessor.get(source) as RealmList<*>,
                            updatePolicy,
                            cache
                        )
                    }
                }
                else -> TODO("Collection type ${property.collectionType} is not supported")
            }
        }
    }

    @Suppress("LongParameterList")
    internal fun assignDynamic(
        target: DynamicMutableRealmObject,
        source: BaseRealmObject,
        updatePolicy: UpdatePolicy,
        cache: ObjectCache
    ) {
        val properties: List<Pair<String, Any?>> = if (source is DynamicRealmObject) {
            if (source is DynamicUnmanagedRealmObject) {
                source.properties.toList()
            } else {
                // We should never reach here. If the object is dynamic and managed we reuse the
                // managed object. Even for embedded object we should not reach here as the parent
                // would also already be managed and we would just have reused that instead of
                // reimporting it
                sdkError("Unexpected import of dynamic managed object")
            }
        } else {
            val companion = realmObjectCompanionOrThrow(source::class)

            @Suppress("UNCHECKED_CAST")
            val members =
                companion.`io_realm_kotlin_fields` as Map<String, KMutableProperty1<BaseRealmObject, Any?>>
            members.map { it.key to it.value.get(source) }
        }
        properties.map {
            RealmObjectHelper.dynamicSetValue(
                target.realmObjectReference!!,
                it.first,
                it.second,
                updatePolicy,
                cache
            )
        }
    }

    /**
     * Get values for non-collection properties by name.
     *
     * This will verify that the requested type (`clazz`) and nullability matches the property
     * properties in the schema.
     */
    internal fun <R : Any> dynamicGet(
        obj: RealmObjectReference<out BaseRealmObject>,
        propertyName: String,
        clazz: KClass<R>,
        nullable: Boolean
    ): R? {
        obj.checkValid()
        val propertyInfo =
            checkPropertyType(
                obj,
                propertyName,
                CollectionType.RLM_COLLECTION_TYPE_NONE,
                clazz,
                nullable
            )
        val realmValue = getValueByKey(obj, propertyInfo.key)
        // Consider moving this dynamic conversion to Converters.kt
        val value = when (clazz) {
            DynamicRealmObject::class,
            DynamicMutableRealmObject::class ->
                realmValueToRealmObject(
                    realmValue,
                    clazz as KClass<out BaseRealmObject>,
                    obj.mediator,
                    obj.owner
                )
            else -> primitiveTypeConverters.getValue(clazz).realmValueToPublic(realmValue)
        }
        return value?.let {
            @Suppress("UNCHECKED_CAST")
            if (clazz.isInstance(value)) {
                value as R?
            } else {
                throw ClassCastException("Retrieving value of type '${clazz.simpleName}' but was of type '${value::class.simpleName}'")
            }
        }
    }

    internal fun <R : Any> dynamicGetList(
        obj: RealmObjectReference<out BaseRealmObject>,
        propertyName: String,
        clazz: KClass<R>,
        nullable: Boolean
    ): RealmList<R?> {
        obj.checkValid()
        val propertyMetadata = checkPropertyType(
            obj,
            propertyName,
            CollectionType.RLM_COLLECTION_TYPE_LIST,
            clazz,
            nullable
        )
        val operatorType = if (propertyMetadata.type != PropertyType.RLM_PROPERTY_TYPE_OBJECT) {
            ListOperatorType.PRIMITIVE
        } else if (!obj.owner.schemaMetadata[propertyMetadata.linkTarget!!]!!.isEmbeddedRealmObject) {
            ListOperatorType.REALM_OBJECT
        } else {
            ListOperatorType.EMBEDDED_OBJECT
        }
        @Suppress("UNCHECKED_CAST")
        return getListByKey<R>(obj, propertyMetadata.key, clazz, operatorType) as RealmList<R?>
    }

    internal fun <R> dynamicSetValue(
        obj: RealmObjectReference<out BaseRealmObject>,
        propertyName: String,
        value: R,
        updatePolicy: UpdatePolicy = UpdatePolicy.ERROR,
        cache: ObjectCache = mutableMapOf()
    ) {
        obj.checkValid()

        val propertyMetadata = checkPropertyType(obj, propertyName, value)
        val clazz = RealmStorageTypeImpl.fromCorePropertyType(propertyMetadata.type).kClass.let {
            if (it == BaseRealmObject::class) DynamicMutableRealmObject::class else it
        }
        when (propertyMetadata.collectionType) {
            CollectionType.RLM_COLLECTION_TYPE_NONE -> when (propertyMetadata.type) {
                PropertyType.RLM_PROPERTY_TYPE_OBJECT -> {
                    if (obj.owner.schemaMetadata[propertyMetadata.linkTarget!!]!!.isEmbeddedRealmObject) {
                        // FIXME Optimize make key variant of this
                        setEmbeddedRealmObject(
                            obj,
                            propertyName,
                            value as BaseRealmObject?,
                            updatePolicy,
                            cache
                        )
                    } else {
                        // FIXME Optimize make key variant of this
                        setObject(obj, propertyName, value as BaseRealmObject?, updatePolicy, cache)
                    }
                }
                else -> {
                    val realmValue =
                        (primitiveTypeConverters.getValue(clazz) as RealmValueConverter<Any>).publicToRealmValue(
                            value
                        )
                    setValueByKey(obj, propertyMetadata.key, realmValue)
                }
            }
            CollectionType.RLM_COLLECTION_TYPE_LIST -> {
                // We cannot use setList as that requires the type, so we need to retrieve the
                // existing list, wipe it and insert new elements
                @Suppress("UNCHECKED_CAST")
                (dynamicGetList(obj, propertyName, clazz, propertyMetadata.isNullable) as ManagedRealmList<Any?>).run {
                    clear()
                    operator.insertAll(
                        size,
                        value as RealmList<*>,
                        updatePolicy,
                        cache
                    )
                }
            }
        }
    }

    private fun checkPropertyType(
        obj: RealmObjectReference<out BaseRealmObject>,
        propertyName: String,
        collectionType: CollectionType,
        elementType: KClass<*>,
        nullable: Boolean
    ): PropertyMetadata {
        val realElementType = elementType.realmStorageType()
        return obj.metadata.getOrThrow(propertyName).also { propertyInfo ->
            val kClass = RealmStorageTypeImpl.fromCorePropertyType(propertyInfo.type).kClass
            if (collectionType != propertyInfo.collectionType ||
                realElementType != kClass ||
                nullable != propertyInfo.isNullable
            ) {
                throw IllegalArgumentException(
                    "Trying to access property '${obj.className}.$propertyName' as type: '${
                    formatType(
                        collectionType,
                        realElementType,
                        nullable
                    )
                    }' but actual schema type is '${
                    formatType(
                        propertyInfo.collectionType,
                        kClass,
                        propertyInfo.isNullable
                    )
                    }'"
                )
            }
        }
    }

    private fun checkPropertyType(
        obj: RealmObjectReference<out BaseRealmObject>,
        propertyName: String,
        value: Any?
    ): PropertyMetadata {
        return obj.metadata.getOrThrow(propertyName).also { propertyInfo ->
            val collectionType =
                if (value is RealmList<*>) CollectionType.RLM_COLLECTION_TYPE_LIST else CollectionType.RLM_COLLECTION_TYPE_NONE
            val realmStorageType = RealmStorageTypeImpl.fromCorePropertyType(propertyInfo.type)
            val kClass = realmStorageType.kClass
            @Suppress("ComplexCondition")
            if (collectionType != propertyInfo.collectionType ||
                // We cannot retrieve the element type info from a list, so will have to rely on lower levels to error out if the types doesn't match
                collectionType == CollectionType.RLM_COLLECTION_TYPE_NONE && (
                    (value == null && !propertyInfo.isNullable) ||
                        (
                            value != null && (
                                (
                                    realmStorageType == RealmStorageType.OBJECT && value !is BaseRealmObject
                                    ) ||
                                    (realmStorageType != RealmStorageType.OBJECT && value!!::class.realmStorageType() != kClass)
                                )
                            )
                    )
            ) {
                throw IllegalArgumentException(
                    "Property '${obj.className}.$propertyName' of type '${
                    formatType(
                        propertyInfo.collectionType,
                        kClass,
                        propertyInfo.isNullable
                    )
                    }' cannot be assigned with value '$value' of type '${
                    formatType(
                        collectionType,
                        value?.let { it::class } ?: Nothing::class,
                        value == null
                    )
                    }'"
                )
            }
        }
    }

    private fun formatType(
        collectionType: CollectionType,
        elementType: KClass<*>,
        nullable: Boolean
    ): String {
        val elementTypeString = elementType.toString() + if (nullable) "?" else ""
        return when (collectionType) {
            CollectionType.RLM_COLLECTION_TYPE_NONE -> elementTypeString
            CollectionType.RLM_COLLECTION_TYPE_LIST -> "RealmList<$elementTypeString>"
            else -> TODO("Unsupported collection type: $collectionType")
        }
    }
}
