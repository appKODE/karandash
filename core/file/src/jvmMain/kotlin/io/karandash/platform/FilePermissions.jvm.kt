package io.karandash.platform

import kotlinx.io.files.Path
import java.io.File

actual fun setExecutable(path: Path) {
    File(path.toString()).setExecutable(true)
}
