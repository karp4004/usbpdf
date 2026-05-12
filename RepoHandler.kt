import org.gradle.api.artifacts.dsl.RepositoryHandler
import java.io.File
import java.net.URI

/* ATTENTION:
   При добавлении репозиториев надо завести задачу на девопс и дождаться ее выполнения.
   Пример задачи: https://task.uralsib.ru/browse/UCM-37506

   Также для доступности на ПУД нужно дополнить инструкцию по настройке ПУД (README_PUD.md) и
   добавить новый url в CERT_URLS внутри configure_proxy_pud.sh.
 */

object RepoHandler {
    fun config(projectRepoPath: String): RepositoryHandler.() -> Unit = {
        google()
        maven { url = URI("https://jitpack.io") }
        mavenCentral()
        maven { url = File(projectRepoPath).toURI() }
        maven { url = URI("https://nexus.paycontrol.org/repository/android/") }
        maven { url = URI("https://artifactory-external.vkpartner.ru/artifactory/maven") }
        maven { url = URI("https://developer.huawei.com/repo/") }
        maven { url = URI("https://maven.scijava.org/content/repositories/public/") }
    }
}

com.github.voghDev:PdfViewPager:1.1.2

implementation 'com.github.afreakyelf:PdfViewer:2.1.1'


implementation("com.github.mhiew:android-pdf-viewer:3.2.0-beta.3") {
    exclude(group = "com.github.barteksc", module = "pdfium-android")
}

// Подменяем pdfium на обновлённый
implementation("com.github.mhiew:pdfium-android:1.9.2")


zipalign -c -P 16 -v 4 app/build/outputs/apk/release/app-release.apk
