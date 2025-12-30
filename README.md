# SSReducer

SSReducer is a **program reduction** tool. It repeatedly removes parts of the input program while preserving a user-defined property (for example: “still reproduces a crash”, “still fails compilation”, “still triggers a warning”).

This repository contains two reducers/use cases:

- **Java source reduction** (implemented in `KotlinJavaSSReducer`, currently: **Java only**)
- **C/C++ source reduction** (implemented in `CppSSReducer`, currently: **C only**, intended to be shipped as a **CLion plugin**)

The reduction oracle is defined by a user-provided script (`predict.sh`). If the script returns exit code `0`, the candidate is considered **valid** and the reducer keeps the change; otherwise the change is reverted.

---

## 1) Kotlin Java Source Code Reduction (developing, currently: Java-only)

### Overview

The Java reducer minimizes Java source code while keeping your `predict.sh` condition true.

Internally, SSReducer repeatedly edits/removes code, materializes a candidate project into a temporary directory, and runs your predict script there.

### Quick start (for users)

#### 1) Download

Normal users do **not** need to build the project from source.

Please download the prebuilt distribution from the **GitHub Releases** page, then unpack it. The archive contains:

- a runnable launcher script/binary
- all required libraries

> The exact file name and directory layout may vary by release.

#### 2) Prepare a working directory

SSReducer enforces that both:

- `--predict/-p` points to a script **inside the current working directory**
- all `--sourceRoots` are also **inside the current working directory**

This is required because SSReducer copies all files into a temporary directory using paths relative to the working directory.

#### 3) Write `predict.sh`

The script is **provided by you**. SSReducer only cares about its exit code:

- exit code `0` => PASS (the candidate still satisfies your requirement)
- exit code `!= 0` => FAIL

Important behavior:

- The script is executed **inside a temporary directory** that contains the candidate sources.
- Environment variables are inherited from the parent process.
- A timeout is enforced by `--predictTimeOut`.

#### 4) Run

Run the launcher from the downloaded release (the command name depends on the release artifact), for example:

```bash
./SSReducer KotlinJavaSSReducer \
  --predict ./predict.sh \
  --sourceRoots ./path/to/your/java/sources
```

> Tip: `--sourceRoots` can be repeated.

### Command line arguments (Java reducer)

SSReducer is a launcher:

```text
SSReducer <ReducerName> [ReducerArgs...]
```

For Java reduction, `ReducerName` is `KotlinJavaSSReducer`.

Below is the full list of reducer arguments.

#### Common arguments (shared by reducers)

These options are implemented in `CommonReducer`.

- `--predict`, `-p` (required)
  - Type: file
  - Meaning: path to your predict script.
  - Constraints:
    - Must exist and be readable.
    - Must be located **inside the current working directory**.

- `--predictTimeOut`
  - Type: long (milliseconds)
  - Default: `10000`
  - Meaning: timeout for a single predict run.
  - Notes:
    - If the predict process doesn’t finish before the timeout, it is killed and the run is treated as FAIL.

- `--out`, `-o`
  - Type: directory path
  - Default: if not set, SSReducer creates a unique directory named like `ssreducer`, `ssreducer_1`, ... under the working directory.
  - Meaning: where to write the final reduced result (and optionally intermediate results when `--saveTemps=true`).
  - Constraints:
    - If the path exists, it must be a directory.

- `--sourceRoots` (repeatable)
  - Type: file or directory
  - Default: empty list
  - Meaning: input roots that contain the sources to reduce.
  - Constraints:
    - Each path must exist and be readable.
    - Each path must be located **inside the current working directory**.

- `--saveTemps`
  - Type: boolean
  - Default: `false`
  - Meaning: if enabled, SSReducer saves each predict attempt’s materialized files into `--out/<predictIndex>_<predictResult>_<exitCode>`.

#### Java reducer arguments

These options are implemented in `KotlinJavaSSReducer`.

- `--jvmTarget`, `-jt`
  - Type: enum (`org.jetbrains.kotlin.config.JvmTarget`)
  - Default: `JvmTarget.DEFAULT`
  - Meaning: JVM target used when creating the analysis session.

- `--languageVersion`, `-lv`
  - Type: enum (`org.jetbrains.kotlin.config.LanguageVersion`)
  - Default: `LanguageVersion.FIRST_NON_DEPRECATED`
  - Meaning: language version used by the analysis session.

- `--apiVersion`, `-av`
  - Type: enum (`org.jetbrains.kotlin.config.LanguageVersion`)
  - Default: `LanguageVersion.FIRST_NON_DEPRECATED`
  - Meaning: API version used by the analysis session.

- `--jdkHome`
  - Type: directory
  - Default: `java.home` of the current process
  - Meaning: JDK home used by the analysis session.

- `--moduleName`
  - Type: string
  - Default: `<mock-module-name>`
  - Meaning: module name used by the analysis session.

- `--libraries`, `-l` (repeatable)
  - Type: directory
  - Default: empty list
  - Meaning: directories containing libraries for analysis.

- `--friends`, `-f` (repeatable)
  - Type: directory
  - Default: empty list
  - Meaning: friend paths for analysis.

### Developer entry (build & run from sources)

For developers who want to compile and run directly from this repository:

```bash
./gradlew run --args='KotlinJavaSSReducer \
  --predict ./predict.sh \
  --sourceRoots ./path/to/your/java/sources'
```

Build artifacts locally (for development use):

```bash
./gradlew build
```

Optionally create a local distribution (again: development use only):

```bash
./gradlew installDist
```

---

## 2) C/C++ Reduction (currently: C-only)

(TODO: will be documented later; planned to be released as a CLion plugin.)
