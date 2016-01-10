@echo off
cd %~dp0

SETLOCAL
if exist .nuget RD /S /Q .nuget
if exist packages RD /S /Q packages
if exist artifacts RD /S /Q artifacts

echo Downloading latest version of NuGet.exe...
md .nuget
@powershell -NoProfile -ExecutionPolicy unrestricted -Command "$ProgressPreference = 'SilentlyContinue'; Invoke-WebRequest 'https://dist.nuget.org/win-x86-commandline/latest/nuget.exe' -OutFile .nuget\NuGet.exe"

echo Downloading KoreBuild...
.nuget\nuget.exe install KoreBuild -ExcludeVersion -o packages -nocache -pre

echo Downloading Sake...
.nuget\NuGet.exe install Sake -ExcludeVersion -Source https://www.nuget.org/api/v2/ -Out packages

echo Updating DNVM...
CALL packages\KoreBuild\build\dnvm update-self

echo Downloading DNX...
CALL packages\KoreBuild\build\dnvm install latest -runtime CoreCLR -arch x86 -alias default
CALL packages\KoreBuild\build\dnvm install default -runtime CLR -arch x86 -alias default

echo Running Sake Build...
packages\Sake\tools\Sake.exe -I packages\KoreBuild\build -f makefile.shade %*

echo Packing nugets...
call dnu pack src\ZooKeeperNetEx --configuration release --out artifacts\nugets
call dnu pack src\ZooKeeperNetEx.Recipes --configuration release --out artifacts\nugets