import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

// Patch only the embedded AGSL source in the pinned upstream AAR. Keep its
// dependency metadata, blur pipeline, capture bounds and refraction code intact.
@CacheableTransform
abstract class CorrectGlassAmbient : TransformAction<CorrectGlassAmbient.Parameters> {
    interface Parameters : TransformParameters {
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        val before: RegularFileProperty
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        val after: RegularFileProperty
    }

    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val source = inputArtifact.get().asFile
        if (source.name != "haze-glass.aar" && !source.name.startsWith("haze-glass-android-")) {
            outputs.file(source)
            return
        }
        val original = source.readBytes()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }
        check(sha256 == "2b06240d6b5752c5e32aa03fa94ab1f62c71715aeec714bfd50e09384c38f68f") {
            "Haze Glass artifact changed. Review the shader patch against the new source before updating its fingerprint."
        }
        val before = parameters.before.get().asFile.readText().replace("\r\n", "\n").trimEnd()
        val after = parameters.after.get().asFile.readText().replace("\r\n", "\n").trimEnd()
        var replacements = 0
        fun patchClass(bytes: ByteArray): ByteArray {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val result = ByteArrayOutputStream()
            val output = DataOutputStream(result)
            check(input.readInt() == 0xCAFEBABE.toInt())
            output.writeInt(0xCAFEBABE.toInt())
            output.writeShort(input.readUnsignedShort()) // minor
            output.writeShort(input.readUnsignedShort()) // major
            val count = input.readUnsignedShort()
            output.writeShort(count)
            var index = 1
            while (index < count) {
                val tag = input.readUnsignedByte()
                output.writeByte(tag)
                when (tag) {
                    1 -> {
                        val text = input.readUTF()
                        if (text.contains(before)) {
                            replacements += text.split(before).size - 1
                            output.writeUTF(text.replace(before, after))
                        } else output.writeUTF(text)
                    }
                    3, 4, 9, 10, 11, 12, 17, 18 -> output.writeInt(input.readInt())
                    5, 6 -> { output.writeLong(input.readLong()); index++ }
                    7, 8, 16, 19, 20 -> output.writeShort(input.readUnsignedShort())
                    15 -> { output.writeByte(input.readUnsignedByte()); output.writeShort(input.readUnsignedShort()) }
                    else -> error("Unsupported class constant tag: $tag")
                }
                index++
            }
            input.copyTo(output)
            return result.toByteArray()
        }

        fun rewriteZip(bytes: ByteArray, edit: (String, ByteArray) -> ByteArray): ByteArray {
            val result = ByteArrayOutputStream()
            ZipOutputStream(result).use { output ->
                ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
                    var entry = input.nextEntry
                    while (entry != null) {
                        output.putNextEntry(ZipEntry(entry.name).apply { time = 0L })
                        output.write(edit(entry.name, input.readBytes()))
                        output.closeEntry()
                        entry = input.nextEntry
                    }
                }
            }
            return result.toByteArray()
        }

        val patched = rewriteZip(original) { name, bytes ->
            if (name != "classes.jar") bytes else rewriteZip(bytes) { className, classBytes ->
                if (className == "dev/chrisbanes/haze/glass/GlassShaders.class") patchClass(classBytes)
                else classBytes
            }
        }
        check(replacements == 1) {
            "Expected one Haze 2.0.0 ambient shader replacement, found $replacements. Review the patch before changing Haze versions."
        }
        outputs.file("${source.nameWithoutExtension}-ambient-fixed.aar").writeBytes(patched)
    }
}

val correctedAmbient = Attribute.of("com.nogirelay.haze.correctedAmbient", Boolean::class.javaObjectType)
val artifactType = Attribute.of("artifactType", String::class.java)
dependencies {
    attributesSchema.attribute(correctedAmbient)
    artifactTypes.maybeCreate("aar").attributes.attribute(correctedAmbient, false)
    registerTransform(CorrectGlassAmbient::class) {
        from.attribute(correctedAmbient, false).attribute(artifactType, "aar")
        to.attribute(correctedAmbient, true).attribute(artifactType, "aar")
        parameters {
            before.set(rootProject.layout.projectDirectory.file("patches/haze-glass/ambient-before.agsl"))
            after.set(rootProject.layout.projectDirectory.file("patches/haze-glass/ambient-after.agsl"))
        }
    }
}
configurations.configureEach {
    if (isCanBeResolved) attributes.attribute(correctedAmbient, true)
}
