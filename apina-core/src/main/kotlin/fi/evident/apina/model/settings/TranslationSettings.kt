package fi.evident.apina.model.settings

import fi.evident.apina.model.type.ApiTypeName
import fi.evident.apina.utils.PatternSet
import java.util.*

/**
 * Various settings guiding the translation.
 */
class TranslationSettings {

    val blackBoxClasses = PatternSet()
    val controllersToProcess = PatternSet()
    private val importsByModule = TreeMap<String, ImportDefinition>()
    private val importedTypes = TreeSet<ApiTypeName>()
    var platform = Platform.ANGULAR2
    var enumMode = EnumMode.ENUM

    fun isBlackBoxClass(name: String) = name in blackBoxClasses

    fun addImport(moduleName: String, types: Collection<String>) {
        val importDefinition = importsByModule.getOrPut(moduleName) { ImportDefinition(moduleName) }

        for (type in types) {
            val typeName = ApiTypeName(type)
            if (!importedTypes.add(typeName))
                throw IllegalArgumentException("type $typeName is already imported")

            importDefinition.addType(typeName)
        }
    }

    val imports: Collection<ImportDefinition>
        get() = importsByModule.values

    fun isImported(typeName: ApiTypeName) = typeName in importedTypes
}
