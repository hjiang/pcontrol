{
  description = "pcontrol - parental control server + Android client";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };
        androidComposition = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ "34" ];
          buildToolsVersions = [ "34.0.0" ];
          includeEmulator = false;
          includeSystemImages = false;
        };
      in {
        devShells.default = pkgs.mkShell {
          packages = with pkgs; [
            # server
            go
            gopls
            gotools
            sqlite
            # android
            jdk17
            gradle
            android-tools
            androidComposition.androidsdk
            kotlin-language-server
          ];
          ANDROID_HOME = "${androidComposition.androidsdk}/libexec/android-sdk";
          shellHook = ''
            export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/34.0.0/aapt2"
            echo "pcontrol dev shell: go $(go version | cut -d' ' -f3), java $(java -version 2>&1 | head -1)"
          '';
        };
      });
}
