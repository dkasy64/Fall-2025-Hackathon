1. Download Maven apache-maven-3.9.11-bin.zip on https://maven.apache.org/download.cgi, unzip to a directory.

2. Open System Properties > Advanced > Environment Variables

3. Under System Variables, Select New, enter the following:

Variable Name: M2_HOME
Variable Value [directory], you can also browse directory

4. Select "PATH" in System Variables, select "Edit", and select "New". Type %M2_HOME%\bin.

5. Install JDK 17

6. Under System Variables, Select New, enter the following:
Variable Name: JAVA_HOME
Variable Value [directory of JDK (usually in program files)], you can also browse directory

You should now be able to run this project