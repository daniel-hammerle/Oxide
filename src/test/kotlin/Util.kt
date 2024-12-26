import com.language.compilation.SignatureString
import com.language.compilation.Type

fun jvmType(name: String, vararg generics: Pair<String, Type.Broad>): Type.JvmType = Type.BasicJvmType(SignatureString(name), generics.toMap())

fun listType(itemType: Type) = listType(Type.Broad.Known(itemType))
fun listType(itemType: Type.Broad) = jvmType("java::util::List", "E" to itemType)

fun union(vararg types: Type) = Type.Union(types.toSet())

val UnsetListType = listType(Type.Broad.Unset)
