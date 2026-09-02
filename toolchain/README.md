# Project toolchain lock

All shared build and test evidence must use this exact baseline:

- Eclipse Temurin OpenJDK `1.8.0_502` (`Temurin-8.0.502+7`)
- `javac 1.8.0_502`
- the 14 SystemJ JARs listed in `systemj-project.sha256`

The JAR files are distributed separately and are not committed to this
repository. Run `tools/verify_project_toolchain.py` before compiling. Do not
mix JARs from another SystemJ distribution or run the SystemJ compiler with a
newer Java release: this compiler snapshot can generate different Java source
when hosted by a different JDK.
