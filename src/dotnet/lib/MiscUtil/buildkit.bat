@echo off
set NUNIT_DIR="C:\Program Files\TestDriven.NET 2.0\NUnit"

echo Cleaning
if exist tmp rmdir /s /q tmp
mkdir tmp
cd tmp
mkdir dist
echo Fetching
svn export -q svn://netdisk/miscutil/trunk MiscUtil
svn log svn://netdisk/miscutil/trunk > MiscUtil\changelog.txt
echo Packaging source
zip -q -r dist\src.zip MiscUtil
cd MiscUtil
echo Building
msbuild /nologo /verbosity:quiet /p:Configuration=Release MiscUtil.sln
echo Testing
%NUNIT_DIR%\nunit-console.exe /nologo MiscUtil.UnitTests\bin\Release\MiscUtil.UnitTests.dll
cd ..
echo Packaging binaries
mkdir bin
copy MiscUtil\MiscUtil\bin\Release\MiscUtil.dll bin > NUL
copy MiscUtil\MiscUtil\bin\Release\MiscUtil.dll dist > NUL
copy MiscUtil\MiscUtil\bin\Release\MiscUtil.xml bin > NUL
copy ..\licence.txt bin > NUL
copy ..\readme.txt bin > NUL
cd bin
zip -q ..\dist\bin.zip *.*
cd ..\..
echo Done!
