package fi.evident.apina.java.model.type

import java.util.*

class TypeSchema {

    private val _variables = ArrayList<JavaType.Variable>()
    private val boundMap = HashMap<JavaType.Variable, MutableList<JavaType>>()

    val variables: List<JavaType.Variable>
        get() = _variables

    fun add(v: JavaType.Variable) {
        val old = boundMap.putIfAbsent(v, ArrayList<JavaType>())
        if (old != null)
            throw IllegalArgumentException("tried to add duplicate variable: $v")
        _variables.add(v)
    }

    fun addBound(v: JavaType.Variable, bound: JavaType) {
        val bounds = boundMap[v] ?: throw IllegalArgumentException("unknown variable $v")
        bounds.add(bound)
    }

    fun getTypeBounds(v: JavaType.Variable): List<JavaType> = boundMap[v] ?: emptyList()

    override fun toString() = boundMap.toString()

    val isEmpty: Boolean
        get() = boundMap.isEmpty()

    fun apply(arguments: List<JavaType>): TypeEnvironment {
        if (arguments.size != variables.size)
            throw IllegalArgumentException("expected ${variables.size} arguments, but got ${arguments.size}")

        val env = TypeEnvironment.empty()

        variables.forEachIndexed { i, v ->
            env[v] = arguments[i]
        }

        return env
    }
}
