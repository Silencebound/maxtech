plugins {
    id("maxtech.common")
}

dependencies {
    // Farms builds on Lib's public api only (FM1): compileOnly against the api jar, so a use of Lib's internals fails to
    // compile. At dev runtime Lib is loaded as its own mod; in a pack it is Lib's jar.
    compileOnly(project(":lib:common", "modApi"))
    "apiCompileOnly"(project(":lib:common", "modApi"))
}
