# Run the JVM unit tests (core + sstp) in Docker.
& "$PSScriptRoot\gradle.ps1" :core:test :sstp:test
