import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode

// AlarmeHA: o Fossify Commons mostra o aviso "You are using a fake version of the app"
// (e bloqueia a personalização de cores) quando o pacote não começa com "org.fossify.".
// Como o AlarmeHA é um fork legítimo (GPL-3.0) com applicationId próprio, neutralizamos só
// essas verificações no bytecode do Commons durante o build, sem copiar a biblioteca.
class AlarmeHaRemoveFakeVersionCheckPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.extensions.getByType(AndroidComponentsExtension::class.java).onVariants { variant ->
            variant.instrumentation.transformClassesWith(
                AlarmeHaRemoveFakeVersionCheck::class.java,
                InstrumentationScope.ALL
            ) {}
            variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COPY_FRAMES)
        }
    }
}

abstract class AlarmeHaRemoveFakeVersionCheck : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun isInstrumentable(classData: ClassData) = classData.className in FakeVersionCheckPatcher.targetClasses

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor {
        return object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            override fun visitMethod(
                access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?
            ): MethodVisitor {
                val next = super.visitMethod(access, name, descriptor, signature, exceptions)
                return object : MethodNode(Opcodes.ASM9, access, name, descriptor, signature, exceptions) {
                    override fun visitEnd() {
                        FakeVersionCheckPatcher.patch(instructions)
                        accept(next)
                    }
                }
            }
        }
    }
}

object FakeVersionCheckPatcher {
    val targetClasses = setOf(
        "org.fossify.commons.activities.BaseSimpleActivity",
        "org.fossify.commons.activities.CustomizationActivity",
        "org.fossify.commons.compose.extensions.ActivityExtensionsKt",
    )

    private fun nextReal(node: AbstractInsnNode?): AbstractInsnNode? {
        var n = node?.next
        while (n != null && n.opcode == -1) n = n.next
        return n
    }

    fun patch(instructions: InsnList) {
        for (insn in instructions.toArray()) {
            if (insn !is LdcInsnNode) continue
            when (insn.cst) {
                // packageName.contains("yfissof".reversed(), true) -> contains("") == true
                "yfissof" -> insn.cst = ""
                // packageName.startsWith("org.fossify.", true) -> startsWith("") == true
                "org.fossify." -> {
                    val flag = nextReal(insn)
                    val call = nextReal(flag)
                    if (flag?.opcode == Opcodes.ICONST_1 && call is MethodInsnNode &&
                        call.owner == "kotlin/text/StringsKt" && call.name == "startsWith"
                    ) {
                        insn.cst = ""
                    }
                }
            }
        }
    }
}
