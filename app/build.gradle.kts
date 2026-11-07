import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.voiceassistant"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.voiceassistant"
        minSdk = 28
        targetSdk = 37
        versionCode = 24
        versionName = "5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        /**
         * 随包内置的智谱 Key。
         *
         * 从 `local.properties`（已在 .gitignore 里）读，编译时注入 BuildConfig：
         * 打出来的 APK 自带这个 Key，谁装上都能直接用；
         * 源码仓库里看不到它，想把项目发给别人也不用先删 Key。
         *
         * 没配这一项时是空串，应用照常跑，只是要用户自己在「关于 → AI 模型」里填。
         */
        val localProps = Properties().apply {
            val file = rootProject.file("local.properties")
            if (file.exists()) file.inputStream().use { input -> load(input) }
        }
        val builtInKey = localProps.getProperty("glm.api.key").orEmpty()
        buildConfigField("String", "GLM_API_KEY", "\"$builtInKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}