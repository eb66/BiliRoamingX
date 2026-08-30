package app.revanced.patches.bilibili.misc.protobuf.patch

import app.revanced.patcher.data.BytecodeContext
import app.revanced.patcher.extensions.InstructionExtensions.replaceInstruction
import app.revanced.patcher.patch.BytecodePatch
import app.revanced.patcher.patch.annotation.CompatiblePackage
import app.revanced.patcher.patch.annotation.Patch
import app.revanced.patcher.util.proxy.mutableTypes.MutableMethod
import app.revanced.patches.bilibili.utils.*
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Patch(
    name = "Unlock ProtoBuf",
    description = "公开ProtoBuf实体构建方法",
    compatiblePackages = [
        CompatiblePackage(name = "tv.danmaku.bili")
    ]
)
object UnlockProtobufPatch : BytecodePatch() {
    override fun execute(context: BytecodeContext) {
        val methodNameRegex = Regex("""^((set|add|remove|clear|merge|getMutable)\w+)|<init>$""")
        // 被公开的方法签名集合；构造器不收录，其调用点必须保持 invoke-direct
        val publicized = HashSet<String>()
        fun keyOf(definingClass: CharSequence, name: CharSequence,
                  params: List<CharSequence>, ret: CharSequence) =
            "$definingClass->$name(${params.joinToString("")})$ret"

        context.classes.filter { it.superclass == "Lcom/google/protobuf/GeneratedMessageLite;" }
            .flatMap { it.proxy(context).methods }.forEach { m ->
                // step 1, private to public
                if (m.accessFlags.isPrivate()
                    && !m.accessFlags.isStatic()
                    && m.name.matches(methodNameRegex)
                ) {
                    m.accessFlags = m.accessFlags.toPublic()
                    if (m.name != "<init>")
                        publicized.add(keyOf(m.definingClass, m.name, m.parameterTypes, m.returnType))
                }
                else if (m.accessFlags.isSynthetic() && m.returnType.let { it == "V" || it == "Ljava/util/Map;" }) {
                    val inst = m.implementation!!.instructions[0]
                    // step 2, invoke-direct to invoke-virtual
                    if (inst.opcode == Opcode.INVOKE_DIRECT) {
                        val args = (inst as BuilderInstruction35c).args()
                        m.replaceInstruction(
                            0, """
                                invoke-virtual {$args}, ${inst.reference}
                            """.trimIndent()
                        )
                    }
                }
            }

        // step 2.5, 全局修正调用点。
        // 原 step 2 只扫描 protobuf 类自身的合成桥接方法的第 0 条指令；9.8.0 上 R8 把这些
        // 访问器内联进了外部类（例如 AppInnerPushManagerV2.init），残留的 invoke-direct
        // 指向已被公开的方法，运行时抛 IncompatibleClassChangeError。
        if (publicized.isNotEmpty()) {
            fun ReferenceInstruction.publicizedTarget(): Boolean {
                val ref = reference as? MethodReference ?: return false
                return keyOf(ref.definingClass, ref.name, ref.parameterTypes, ref.returnType) in publicized
            }

            fun isDirectCallToPublicized(inst: Any?): Boolean {
                val i = inst as? ReferenceInstruction ?: return false
                val op = (inst as com.android.tools.smali.dexlib2.iface.instruction.Instruction).opcode
                if (op != Opcode.INVOKE_DIRECT && op != Opcode.INVOKE_DIRECT_RANGE) return false
                return i.publicizedTarget()
            }

            context.classes.toList().forEach { classDef ->
                val mayNeedFix = classDef.methods.any { m ->
                    m.implementation?.instructions?.any { isDirectCallToPublicized(it) } ?: false
                }
                if (!mayNeedFix) return@forEach
                classDef.proxy(context).methods.forEach fixMethod@{ m ->
                    val impl = m.implementation ?: return@fixMethod
                    val indices = impl.instructions.withIndex()
                        .filter { (_, inst) -> isDirectCallToPublicized(inst) }
                        .map { it.index }.toList()
                    indices.forEach { idx ->
                        val inst = impl.instructions[idx]
                        val ref = (inst as ReferenceInstruction).reference
                        val smali = if (inst.opcode == Opcode.INVOKE_DIRECT) {
                            "invoke-virtual {${(inst as BuilderInstruction35c).args()}}, $ref"
                        } else {
                            val r = inst as BuilderInstruction3rc
                            val last = r.startRegister + r.registerCount - 1
                            "invoke-virtual/range {v${r.startRegister} .. v$last}, $ref"
                        }
                        m.replaceInstruction(idx, smali)
                    }
                }
            }
        }

        // add back XXX method copy from executeXXX method
        context.classes.filter {
            it.type.startsWith("Lcom/bapis") && it.type.endsWith("Moss;")
        }.forEach { c ->
            c.proxy(context).run {
                val newMethods = mutableListOf<MutableMethod>()
                methods.forEach { m ->
                    if (m.name.startsWith("execute") && m.name.length > 7 && m.returnType != "V") {
                        val noPrefixName = m.name.substringAfter("execute").replaceFirstChar { it.lowercaseChar() }
                        m.cloneMutable(name = noPrefixName).takeIf { mm ->
                            methods.none { it == mm }
                        }?.let { newMethods.add(it) }
                    }
                }
                methods.addAll(newMethods)
            }
        }
    }
}
