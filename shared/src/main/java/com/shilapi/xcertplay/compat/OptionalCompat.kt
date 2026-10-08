package com.shilapi.xcertplay.compat

/**
 * `java.util.Optional` 的兼容实现。
 *
 * `java.util.Optional` 仅在 API 24+ 可用（低版本需 desugaring）。
 * 这里提供等价语义，保证 API 17 可运行。
 *
 * @author TRAE AI
 * @since 2026-10-08
 */
class OptionalCompat<T> private constructor(private val value: T?) {

    companion object {
        /**
         * 创建空 Optional。
         */
        @JvmStatic
        fun <T> empty(): OptionalCompat<T> = OptionalCompat(null)

        /**
         * 创建包含非空值的 Optional，值为 null 时抛异常。
         */
        @JvmStatic
        fun <T> of(value: T): OptionalCompat<T> {
            requireNotNull(value) { "Value cannot be null" }
            return OptionalCompat(value)
        }

        /**
         * 创建可能为空的 Optional。
         */
        @JvmStatic
        fun <T> ofNullable(value: T?): OptionalCompat<T> = OptionalCompat(value)
    }

    /**
     * 检查是否有值。
     */
    fun isPresent(): Boolean = value != null

    /**
     * 检查是否为空。
     */
    fun isEmpty(): Boolean = value == null

    /**
     * 获取值（如果存在），否则抛 [NoSuchElementException]。
     */
    fun get(): T = value ?: throw NoSuchElementException("No value present")

    /**
     * 获取值或返回默认值。
     */
    fun orElse(other: T): T = value ?: other

    /**
     * 获取值或返回 supplier 提供的值。
     */
    fun orElseGet(supplier: () -> T): T = value ?: supplier()

    /**
     * 获取值或抛出 supplier 提供的异常。
     */
    fun <X : Throwable> orElseThrow(exceptionSupplier: () -> X): T =
        value ?: throw exceptionSupplier()

    /**
     * 如果有值，执行操作。
     */
    fun ifPresent(consumer: (T) -> Unit) {
        value?.let(consumer)
    }

    /**
     * 如果有值执行 [consumer]，否则执行 [emptyAction]。
     */
    fun ifPresentOrElse(consumer: (T) -> Unit, emptyAction: () -> Unit) {
        if (value != null) consumer(value) else emptyAction()
    }

    /**
     * 转换值。
     */
    fun <U> map(mapper: (T) -> U): OptionalCompat<U> =
        if (value != null) OptionalCompat.ofNullable(mapper(value))
        else empty()

    /**
     * 扁平转换。
     */
    fun <U> flatMap(mapper: (T) -> OptionalCompat<U>): OptionalCompat<U> =
        if (value != null) mapper(value) else empty()

    /**
     * 过滤值。
     */
    fun filter(predicate: (T) -> Boolean): OptionalCompat<T> =
        if (value != null && predicate(value)) this else empty()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OptionalCompat<*>) return false
        return value == other.value
    }

    override fun hashCode(): Int = value?.hashCode() ?: 0

    override fun toString(): String =
        if (value != null) "Optional[$value]" else "Optional.empty"
}

/**
 * 将可空值转为 [OptionalCompat]。
 */
fun <T> T?.toOptional(): OptionalCompat<T> = OptionalCompat.ofNullable(this)

/**
 * 取出 [OptionalCompat] 中的值，空则返回 null。
 */
fun <T> OptionalCompat<T>.orNull(): T? = if (isPresent()) get() else null
