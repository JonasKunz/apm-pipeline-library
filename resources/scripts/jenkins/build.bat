@echo on

docker -v || echo ''
dotnet --info || echo ''
msbuild || echo ''
nuget --help || echo ''
python --version || echo ''
python2 --version || echo ''
python3 --version || echo ''
c:\python2\bin\python.exe --version || echo ''
c:\python27\bin\python.exe --version || echo ''
c:\python3\bin\python.exe --version || echo ''
c:\python38\bin\python.exe --version || echo ''
choco install vswhere || echo ''
vswhere python.exe || echo ''
py -2 --version || echo ''
py -3 --version || echo ''
vswhere || echo ''
