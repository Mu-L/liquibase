package liquibase.changelog


import liquibase.Scope
import liquibase.change.CheckSum
import liquibase.change.ColumnConfig
import liquibase.change.core.*
import liquibase.changelog.visitor.ChangeExecListener
import liquibase.changelog.visitor.DefaultChangeExecListener
import liquibase.database.core.H2Database
import liquibase.database.core.MockDatabase
import liquibase.parser.ChangeLogParserConfiguration
import liquibase.parser.core.ParsedNode
import liquibase.parser.core.ParsedNodeException
import liquibase.precondition.core.RunningAsPrecondition
import liquibase.sdk.supplier.resource.ResourceSupplier
import liquibase.serializer.core.xml.XMLChangeLogSerializer
import liquibase.serializer.core.yaml.YamlChangeLogSerializer
import liquibase.sql.visitor.ReplaceSqlVisitor
import liquibase.structure.core.Column
import liquibase.util.FileUtil
import org.hamcrest.Matchers
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

import static org.junit.Assert.assertTrue
import static spock.util.matcher.HamcrestSupport.that

class ChangeSetTest extends Specification {

    @Shared
            resourceSupplier = new ResourceSupplier()

    def getDescriptions() {
        when:
        def insertDescription = "insert tableName=test_table"
        def changeSet = new ChangeSet("testId", "testAuthor", false, false, null, null, null, null)

        def insertData1 = new InsertDataChange()
        insertData1.setTableName("test_table")

        def insertData2 = new InsertDataChange()
        insertData2.setTableName("test_table2")

        then:
        changeSet.getDescription() == "empty"

        when:
        changeSet.addChange(insertData1)
        then:
        changeSet.getDescription() == insertDescription

        when:
        changeSet.addChange(insertData2)
        then:
        changeSet.getDescription() == insertDescription+"; insert tableName=test_table2"

        when:
        def createTableChange = new CreateTableChange()
        createTableChange.setTableName("new_table")
        changeSet.addChange(createTableChange)
        then:
        changeSet.getDescription() == insertDescription+"; insert tableName=test_table2; createTable tableName=new_table"
    }

    def generateCheckSum() {
        when:
        def changeSet1 = new ChangeSet("testId", "testAuthor", false, false, null, null, null, null)
        def changeSet2 = new ChangeSet("testId", "testAuthor", false, false, null, null, null, null)

        def change = new AddDefaultValueChange()
        change.setSchemaName("SCHEMA_NAME")
        change.setTableName("TABLE_NAME")
        change.setColumnName("COLUMN_NAME")
        change.setDefaultValue("DEF STRING")
        change.setDefaultValueNumeric("42")
        change.setDefaultValueBoolean(true)
        change.setDefaultValueDate("2007-01-02")

        changeSet1.addChange(change)
        changeSet2.addChange(change)

        CheckSum md5Sum1 = changeSet1.generateCheckSum()

        change.setSchemaName("SCHEMA_NAME2")
        CheckSum md5Sum2 = changeSet2.generateCheckSum()

        then:
        assert !md5Sum1.equals(md5Sum2)
    }

    def isCheckSumValid_validCheckSum() {
        when:
        def changeSet = new ChangeSet("1", "2", false, false, "/test.xml", null, null, null)

        then:
        assertTrue(changeSet.isCheckSumValid(changeSet.generateCheckSum()))
    }

    def isCheckSumValid_invalidCheckSum() {
        when:
        def checkSum = CheckSum.parse("8:asdf")
        def changeSet = new ChangeSet("1", "2", false, false, "/test.xml", null, null, null)

        then:
        assert !changeSet.isCheckSumValid(checkSum)
    }

    def isCheckSumValid_differentButValidCheckSum() {
        when:
        CheckSum checkSum = CheckSum.parse("8:asdf")

        ChangeSet changeSet = new ChangeSet("1", "2", false, false, "/test.xml", null, null, null)
        changeSet.addValidCheckSum(changeSet.generateCheckSum().toString())

        then:
        assert changeSet.isCheckSumValid(checkSum)
    }

    def "load empty node"() {
        when:
        def changeSet = new ChangeSet(new DatabaseChangeLog("com/example/test.xml"))
        def node = new ParsedNode(null, "changeSet").addChildren([id: "1", author: "nvoxland"])
        try {
            changeSet.load(node, resourceSupplier.simpleResourceAccessor)
        } catch (ParsedNodeException e) {
            e.printStackTrace()
        }

        then:
        changeSet.toString(false) == "com/example/test.xml::1::nvoxland"
        changeSet.changes.size() == 0
    }

    def "load node with changeSet properties"() {
        when:
        def changeSet = new ChangeSet(new DatabaseChangeLog("com/example/test.xml"))
        def node = new ParsedNode(null, "changeSet")
        def fields = new ChangeSet(new DatabaseChangeLog()).getSerializableFields()
        def testValue = new HashMap()
        for (param in fields) {
            if (param in ["runAlways", "runOnChange", "failOnError"]) {
                testValue[param] = "true"
            } else if (param == "context") {
                testValue[param] = "test or value"
            } else if (param == "rollback" || param == "changes" || param == "preconditions") {
                continue
            } else if (param == "objectQuotingStrategy") {
                testValue[param] = "QUOTE_ONLY_RESERVED_WORDS"
            } else if (param == "runInTransaction") {
                testValue[param] = "false"
            } else if (param == "runOrder") {
                testValue[param] = "last"
            } else if (param == "ignore") {
                testValue[param] = "true"
            } else {
                testValue[param] = "value for ${param}"
            }
            node.addChild(null, param, testValue[param])
        }
        node.addChild(null, "onValidationFail", "MARK_RAN")
        try {
            changeSet.load(node, resourceSupplier.simpleResourceAccessor)
        } catch (ParsedNodeException e) {
            e.printStackTrace()
        }

        then:
        for (param in fields) {
            if (param == "context") {
                assert changeSet.getSerializableFieldValue(param).toString() == "${testValue[param]}"
            } else if (param in testValue.keySet()) {
                assert changeSet.getSerializableFieldValue(param).toString() == testValue[param]
            }
        }
        changeSet.onValidationFail.toString() == "MARK_RAN"

    }

    def "load node with changes as direct children"() {
        when:
        def changeSet = new ChangeSet(new DatabaseChangeLog("com/example/test.xml"))
        def node = new ParsedNode(null, "changeSet")
                .addChildren([id: "1", author: "nvoxland"])
                .addChild(new ParsedNode(null, "createTable").addChild(null, "tableName", "table_1"))
                .addChild(new ParsedNode(null, "createTable").addChild(null, "tableName", "table_2"))
        try {
            changeSet.load(node, resourceSupplier.simpleResourceAccessor)
        } catch (ParsedNodeException e) {
            e.printStackTrace()
        }

        then:
        changeSet.toString(false) == "com/example/test.xml::1::nvoxland"
        changeSet.changes.size() == 2
        changeSet.changes[0].tableName == "table_1"
        changeSet.changes[0].changeSet == changeSet
        changeSet.changes[1].tableName == "table_2"
    }

    def "load node with unknown change types and strict parsing"() {
        when:
        def changeSet = new ChangeSet(new DatabaseChangeLog("com/example/test.xml"))
        def node = new ParsedNode(null, "changeSet")
                .addChildren([id: "1", author: "nvoxland"])
                .addChild(new ParsedNode(null, "createTable").addChild(null, "tableName", "table_1"))
                .addChild(new ParsedNode(null, "invalid").addChild(null, "tableName", "table_2"))
        changeSet.load(node, resourceSupplier.simpleResourceAccessor)

        then:
        def e = thrown(ParsedNodeException)
        e.message == "Error parsing com/example/test.xml: Unknown change type 'invalid'. Check for spelling or capitalization errors and missing extensions such as liquibase-commercial."
    }

    def "load node with unknown change types and lax parsing"() {
        when:
        def changeSet = new ChangeSet(new DatabaseChangeLog("com/example/test.xml"))
        def node = new ParsedNode(null, "changeSet")
                .addChildren([id: "1", author: "nvoxland"])
                .addChild(new ParsedNode(null, "createTable").addChild(null, "tableName", "table_1"))
                .addChild(new ParsedNode(null, "invalid").addChild(null, "tableName", "table_2"))

        Scope.child([(ChangeLogParserConfiguration.CHANGELOG_PARSE_MODE.getKey()): ChangeLogParserConfiguration.ChangelogParseMode.LAX], {
            ->
            changeSet.load(node, resourceSupplier.simpleResourceAccessor)
        } as Scope.ScopedRunner)


        then:
        notThrown(ParsedNodeException)
        changeSet.getChanges().size() == 1
    }

    def "load node with rollback containing sql as value"() {
        when:
        def changeSet = new ChangeSet(new DatabaseChangeLog("com/example/test.xml"))
        def node = new ParsedNode(null, "changeSet")
                .addChild(new ParsedNode(null, "createTable").addChild(null, "tableName", "table_1"))
                .setValue(new ParsedNode(null, "rollback").setValue("rollback logic here"))
        try {
            changeSet.load(node, resourceSupplier.simpleResourceAccessor)
        } catch (ParsedNodeException e) {
            e.printStackTrace()
        }

        then:
        changeSet.changes.size() == 1
        changeSet.rollback.changes.size() == 1
        ((RawSQLChange) changeSet.rollback.changes[0]).sql == "rollback logic here"
    }

    def "load node with rollback containing change node as value"() {
        when:
        def changeSet = new ChangeSet(new DatabaseChangeLog("com/example/test.xml"))
        def node = new ParsedNode(null, "changeSet")
                .addChild(new ParsedNode(null, "createTable").addChild(null, "tableName", "table_1"))
                .setValue(new ParsedNode(null, "rollback").setValue(new ParsedNode(null, "renameTable").addChild(null, "newTableName", "rename_to_x")))
        try {
            changeSet.load(node, resourceSupplier.simpleResourceAccessor)
        } catch (ParsedNodeException e) {
            e.printStackTrace()
        }

        then:
        changeSet.changes.size() == 1
        changeSet.rollback.changes.size() == 1
        ((RenameTableChange) changeSet.rollback.changes[0]).newTableName == "rename_to_x"
    }

    def "load node with rollback containing collection of change nodes as value"() {
        when:
        def changeSet = new ChangeSet(new DatabaseChangeLog("com/example/test.xml"))
        def node = new ParsedNode(null, "changeSet")
                .addChild(new ParsedNode(null, "createTable").addChild(null, "tableName", "table_1"))
                .setValue([
                new ParsedNode(null, "rollback").setValue(new ParsedNode(null, "renameTable").addChild(null, "newTableName", "rename_to_x")),
                new ParsedNode(null, "rollback").setValue(new ParsedNode(null, "renameTable").addChild(null, "newTableName", "rename_to_y"))
        ])
        try {
            changeSet.load(node, resourceSupplier.simpleResourceAccessor)
        } catch (ParsedNodeException e) {
            e.printStackTrace()
        }

        then:
        changeSet.changes.size() == 1
        changeSet.rollback.changes.size() == 2
        ((RenameTableChange) changeSet.rollback.changes[0]).newTableName == "rename_to_x"
        ((RenameTableChange) changeSet.rollback.changes[1]).newTableName == "rename_to_y"
    }

    def "load node with rollback containing rollback nodes as children"() {
        when:
        def changeSet = new ChangeSet(new DatabaseChangeLog("com/example/test.xml"))
        def node = new ParsedNode(null, "changeSet")
                .addChild(new ParsedNode(null, "createTable").addChild(null, "tableName", "table_1"))
                .addChild(new ParsedNode(null, "rollback").setValue(new ParsedNode(null, "renameTable").addChild(null, "newTableName", "rename_to_a")))
                .addChild(new ParsedNode(null, "rollback").addChild((new ParsedNode(null, "renameTable").addChild(null, "newTableName", "rename_to_b"))))
                .addChild(new ParsedNode(null, "rollback").setValue("rollback sql"))

        try {
            changeSet.load(node, resourceSupplier.simpleResourceAccessor)
        } catch (ParsedNodeException e) {
            e.printStackTrace()
        }

        then:
        changeSet.changes.size() == 1
        changeSet.rollback.changes.size() == 3
        ((RenameTableChange) changeSet.rollback.changes[0]).newTableName == "rename_to_a"
        ((RenameTableChange) changeSet.rollback.changes[1]).newTableName == "rename_to_b"
        ((RawSQLChange) changeSet.rollback.changes[2]).sql == "rollback sql"
    }

    def "load node with rollback containing multiple sql statements in value"() {
        when:
        def changeSet = new ChangeSet(new DatabaseChangeLog("com/example/test.xml"))
        def node = new ParsedNode(null, "changeSet")
                .addChild(new ParsedNode(null, "createTable").addChild(null, "tableName", "table_1"))
                .addChild(new ParsedNode(null, "rollback").setValue("\n--a comment here\nrollback sql 1;\nrollback sql 2\n--final comment"))

        try {
            changeSet.load(node, resourceSupplier.simpleResourceAccessor)
        } catch (ParsedNodeException e) {
            e.printStackTrace()
        }

        then:
        changeSet.changes.size() == 1
        ((RawSQLChange) changeSet.rollback.changes[0]).sql == "rollback sql 1"
        ((RawSQLChange) changeSet.rollback.changes[1]).sql == "rollback sql 2"
        changeSet.rollback.changes.size() == 2
    }

    def "load node with valid checksums as children"() {
        when:
        def changeSet = new ChangeSet(new DatabaseChangeLog("com/example/test.xml"))
        def node = new ParsedNode(null, "changeSet")
                .addChild(null, "validCheckSum", "c2b7b29ce3a75940893cd022501852e2")
                .addChild(null, "validCheckSum", "8:d54da29ce3a75940858cd093501158b8")
        try {
            changeSet.load(node, resourceSupplier.simpleResourceAccessor)
        } catch (ParsedNodeException e) {
            e.printStackTrace()
        }

        then:
        changeSet.validCheckSums.size() == 2
        new ArrayList(changeSet.validCheckSums)[0].toString() == "1:c2b7b29ce3a75940893cd022501852e2"
        new ArrayList(changeSet.validCheckSums)[1].toString() == "8:d54da29ce3a75940858cd093501158b8"
    }

    def "load node with valid checksums in value"() {
        when:
        def changeSet = new ChangeSet(new DatabaseChangeLog("com/example/test.xml"))
        def node = new ParsedNode(null, "changeSet").setValue([
                new ParsedNode(null, "validCheckSum").setValue("c2b7b29ce3a75940893cd022501852e2"),
                new ParsedNode(null, "validCheckSum").setValue("8:d54da29ce3a75940858cd093501158b8")
        ])
        try {
            changeSet.load(node, resourceSupplier.simpleResourceAccessor)
        } catch (ParsedNodeException e) {
            e.printStackTrace()
        }

        then:
        changeSet.validCheckSums.size() == 2
        new ArrayList(changeSet.validCheckSums)[0].toString() == "1:c2b7b29ce3a75940893cd022501852e2"
        new ArrayList(changeSet.validCheckSums)[1].toString() == "8:d54da29ce3a75940858cd093501158b8"
    }

    def "load node with preconditions as child"() {
        when:
        def changeSet = new ChangeSet(new DatabaseChangeLog("com/example/test.xml"))
        try {
            changeSet.load(new ParsedNode(null, "changeSet").addChildren([preConditions: [
                    [runningAs: [username: "my_user"]],
                    [runningAs: [username: "my_other_user"]],
            ]]), resourceSupplier.simpleResourceAccessor)
        } catch (ParsedNodeException e) {
            e.printStackTrace()
        }

        then:
        changeSet.preconditions.nestedPreconditions.size() == 2
        ((RunningAsPrecondition) changeSet.preconditions.nestedPreconditions[0]).username == "my_user"
        ((RunningAsPrecondition) changeSet.preconditions.nestedPreconditions[1]).username == "my_other_user"
    }

    def "load node with preconditions as value"() {
        when:
        def changeSet = new ChangeSet(new DatabaseChangeLog("com/example/test.xml"))
        try {
            changeSet.load(new ParsedNode(null, "changeSet").setValue(new ParsedNode(null, "preConditions").setValue([
                    [runningAs: [username: "my_user"]],
                    [runningAs: [username: "my_other_user"]],
            ])), resourceSupplier.simpleResourceAccessor)
        } catch (ParsedNodeException e) {
            e.printStackTrace()
        }

        then:
        changeSet.preconditions.nestedPreconditions.size() == 2
        ((RunningAsPrecondition) changeSet.preconditions.nestedPreconditions[0]).username == "my_user"
        ((RunningAsPrecondition) changeSet.preconditions.nestedPreconditions[1]).username == "my_other_user"
    }

    def "load with modifySql as value"() {
        when:
        def changeSet = new ChangeSet(new DatabaseChangeLog("com/example/test.xml"))
        try {
            changeSet.load(new ParsedNode(null, "changeSet").setValue([
                    new ParsedNode(null, "modifySql").addChildren([applyToRollback: "true", replace: [replace: "a", with: "b"]]),
                    new ParsedNode(null, "modifySql").addChildren([dbms: "mysql, oracle", context: "live, test", applyToRollback: "false"]).setValue([
                            [replace: [replace: "x1", with: "y1"]],
                            [replace: [replace: "x2", with: "y2"]],
                    ])
            ]), resourceSupplier.simpleResourceAccessor)
        } catch (ParsedNodeException e) {
            e.printStackTrace()
        }

        then:
        changeSet.sqlVisitors.size() == 3
        assert ((ReplaceSqlVisitor) changeSet.sqlVisitors[0]).applyToRollback
        ((ReplaceSqlVisitor) changeSet.sqlVisitors[0]).applicableDbms == null
        ((ReplaceSqlVisitor) changeSet.sqlVisitors[0]).contextFilter == null
        ((ReplaceSqlVisitor) changeSet.sqlVisitors[0]).replace == "a"
        ((ReplaceSqlVisitor) changeSet.sqlVisitors[0]).with == "b"

        that(((ReplaceSqlVisitor) changeSet.sqlVisitors[1]).applicableDbms, Matchers.containsInAnyOrder(["mysql", "oracle"].toArray()))
        ((ReplaceSqlVisitor) changeSet.sqlVisitors[1]).contextFilter.toString() == "live, test"
        ((ReplaceSqlVisitor) changeSet.sqlVisitors[1]).replace == "x1"
        ((ReplaceSqlVisitor) changeSet.sqlVisitors[1]).with == "y1"

        that(((ReplaceSqlVisitor) changeSet.sqlVisitors[2]).applicableDbms, Matchers.containsInAnyOrder(["mysql", "oracle"].toArray()))
        ((ReplaceSqlVisitor) changeSet.sqlVisitors[2]).contextFilter.toString() == "live, test"
        ((ReplaceSqlVisitor) changeSet.sqlVisitors[2]).replace == "x2"
        ((ReplaceSqlVisitor) changeSet.sqlVisitors[2]).with == "y2"
    }

    def "load with empty rollback creates an EmptyChange"() {
        when:
        def changeSet = new ChangeSet(new DatabaseChangeLog("com/example/test.xml"))
        try {
            changeSet.load(new ParsedNode(null, "changeSet").addChild(new ParsedNode(null, "rollback")), resourceSupplier.simpleResourceAccessor)
        } catch (ParsedNodeException e) {
            e.printStackTrace()
        }

        then:
        changeSet.changes.size() == 0
        changeSet.rollback.changes.size() == 1
        changeSet.rollback.changes[0] instanceof EmptyChange
    }

    def "load node with rollback containing only a comment creates an EmptyChange"() {
        when:
        def changeSet = new ChangeSet(new DatabaseChangeLog("com/example/test.xml"))
        try {
            def commentNode = new ParsedNode(null, "comment").setValue("comment here")
            def rollbackNode = new ParsedNode(null, "rollback").addChild(commentNode)
            def changeSetNode = new ParsedNode(null, "changeSet").addChild(rollbackNode)
            changeSet.load(changeSetNode, resourceSupplier.simpleResourceAccessor)
        } catch (ParsedNodeException e) {
            e.printStackTrace()
        }
        then:
        changeSet.changes.size() == 0
        changeSet.rollback.changes.size() == 1
        changeSet.rollback.changes[0] instanceof EmptyChange
    }

    def "load node with rollback containing change node and a comment as value"() {
        when:
        def changeSet = new ChangeSet(new DatabaseChangeLog("com/example/test.xml"))
        def createTableNode = new ParsedNode(null, "createTable").addChild(null, "tableName", "table_1")
        def renameTableNode = new ParsedNode(null, "renameTable").addChild(null, "newTableName", "rename_to_x")
        def commentNode = new ParsedNode(null, "comment").setValue("comment here")
        def rollbackNode = new ParsedNode(null, "rollback").addChild(commentNode).addChild(renameTableNode)
        def changeSetNode = new ParsedNode(null, "changeSet").addChild(createTableNode).addChild(rollbackNode)
        try {
            changeSet.load(changeSetNode, resourceSupplier.simpleResourceAccessor)
        } catch (ParsedNodeException e) {
            e.printStackTrace()
        }

        then:
        changeSet.changes.size() == 1
        changeSet.rollback.changes.size() == 1
        ((RenameTableChange) changeSet.rollback.changes[0]).newTableName == "rename_to_x"
    }

    @Unroll("#featureName with changeSetPath=#changeSetPath")
    def "load with rollback referencing earlier changeSet"() {
        def path = "com/example/test.xml"
        when:
        def changeLog = new DatabaseChangeLog(path)
        changeLog.load(new ParsedNode(null, "databaseChangeLog")
                .addChildren([changeSet: [id: "1", author: "nvoxland", createTable: [tableName: "table1"]]])
                .addChildren([changeSet: [id: "2", author: "nvoxland", createTable: [tableName: "table2"]]])
                .addChildren([changeSet: [id: "3", author: "nvoxland", dropTable: [tableName: "tableX"], rollback: [changeSetId: "2", changeSetAuthor: "nvoxland", changeSetPath: changeSetPath]]])
                , resourceSupplier.simpleResourceAccessor)

        then:
        changeLog.getChangeSet(path, "nvoxland", "3").changes.size() == 1
        ((DropTableChange) changeLog.getChangeSet(path, "nvoxland", "3").changes[0]).tableName == "tableX"
        changeLog.getChangeSet(path, "nvoxland", "3").rollback.changes.size() == 1
        ((CreateTableChange) changeLog.getChangeSet(path, "nvoxland", "3").rollback.changes[0]).tableName == "table2"

        where:
        changeSetPath << ["com/example/test.xml", null]
    }

    def "load with changes in 'changes' node is parsed correctly"() {
        when:
        def changeSet = new ChangeSet(new DatabaseChangeLog("com/example/test.xml"))
        def node = new ParsedNode(null, "changeSet")
                .addChildren([id: "1", author: "nvoxland", changes: [
                new ParsedNode(null, "createTable").addChild(null, "tableName", "table_1"),
                new ParsedNode(null, "createTable").addChild(null, "tableName", "table_2")
        ]])
        try {
            changeSet.load(node, resourceSupplier.simpleResourceAccessor)
        } catch (ParsedNodeException e) {
            e.printStackTrace()
        }

        then:
        changeSet.toString(false) == "com/example/test.xml::1::nvoxland"
        changeSet.changes.size() == 2
        changeSet.changes[0].tableName == "table_1"
        changeSet.changes[1].tableName == "table_2"
    }

    @Unroll("#featureName: #param=#value")
    def "load handles alwaysRun or runAlways"() {
        when:
        def changeSet = new ChangeSet(new DatabaseChangeLog("com/example/test.xml"))
        try {
            changeSet.load(new ParsedNode(null, "changeSet").addChild(null, param, value), resourceSupplier.simpleResourceAccessor)
        } catch (ParsedNodeException e) {
            e.printStackTrace()
        }

        then:
        changeSet.shouldAlwaysRun() == Boolean.valueOf(value)

        where:
        param       | value
        "alwaysRun" | "true"
        "alwaysRun" | "false"
        "runAlways" | "true"
        "runAlways" | "false"
    }

    @Unroll
    def "load handles validCheckSum(s) as a collection or a single value"() {
        when:
        def changeSet = new ChangeSet(new DatabaseChangeLog("com/example/test.xml"))
        try {
            changeSet.load(new ParsedNode(null, "changeSet").addChild(null, param, value), resourceSupplier.simpleResourceAccessor)
        } catch (ParsedNodeException e) {
            e.printStackTrace()
        }

        def expected = value
        if (value instanceof String) {
            expected = [value]
        }

        then:
        that changeSet.getValidCheckSums().collect({it.toString()}), Matchers.containsInAnyOrder(((List) expected).toArray())

        where:
        param            | value
        "validCheckSum"  | "8:a3d6a29ce3a75940858cd093501151d1"
        "validCheckSums" | "8:a3d6a29ce3a75940858cd093501151d1"
        "validCheckSum"  | ["8:a3d6a29ce3a75940858cd093501151d1", "8:b4d6a29de3a75940858cd09350115234"]
        "validCheckSums" | ["8:a3d6a29ce3a75940858cd093501151d1", "8:b4d6a29de3a75940858cd09350115234"]
        "validCheckSum"  | []
        "validCheckSums" | []

    }

    @Unroll("#featureName: #dbmsList=#expectedValues")
    def "check serialization for field dbms"() {
        when:
        def changeSet = new ChangeSet("id1", "author1", false, false, "/test.xml", null, dbmsList, null)

        then:
        that(((String) changeSet.getSerializableFieldValue("dbms")).split(","), Matchers.arrayContainingInAnyOrder(*expectedValues))

        where:
        dbmsList            | expectedValues
        "all"               | ["all"]
        "dbms1, !dbms2"     | ["dbms1", "!dbms2"]
        "dbms1,dbms2,dbms3" | ["dbms1", "dbms2", "dbms3"]
    }

    def "check serialization for field dbms with empty and null value"() {
        when:
        def changeSet = new ChangeSet("id2", "author2", false, false, "/test.xml", null, dbmsList, null)

        then:
        changeSet.getSerializableFieldValue("dbms") == expectedValue

        where:
        dbmsList | expectedValue
        ""       | null
        null     | null
    }

    def isInheritableIgnore() {
        when:
        def changeSet = new ChangeSet("id1", "author1", false, false, "/test.xml", null, null, null)

        then:
        !changeSet.isInheritableIgnore()

        when:
        def parent = new DatabaseChangeLog("com/example/test.xml")
        changeSet = new ChangeSet("id1", "author1", false, false, "/test.xml", null, null, parent)

        then:
        !changeSet.isInheritableIgnore()

        when:
        parent.setIncludeIgnore(true)

        then:
        changeSet.isInheritableIgnore()
    }

    def "execute returns a EXECUTED state when at least one change executes"() {
        when:
        DatabaseChangeLog databaseChangeLog = new DatabaseChangeLog("com/example/test.xml")
        def changeSet =
           new ChangeSet("testId", "testAuthor", false, false, null, null, null, databaseChangeLog)
        def change1 = new CreateTableChange()
        change1.addColumn(new ColumnConfig(new Column("column1")))
        def change2 = new RawSQLChange("ALTER TABLE pendingEmails ALTER COLUMN createdAt datetime2 NOT NULL;")
        change2.setDbms("mssql")
        changeSet.addChange(change1)
        changeSet.addChange(change2)
        databaseChangeLog.addChangeSet(changeSet)
        ChangeExecListener listener = new DefaultChangeExecListener()

        then:
        changeSet.execute(databaseChangeLog, listener, new MockDatabase()) == ChangeSet.ExecType.EXECUTED
        listener.getDeployedChanges(changeSet).size() == 1

        when:
        databaseChangeLog = new DatabaseChangeLog("com/example/test.xml")
        changeSet =
                new ChangeSet("testId", "testAuthor", false, false, null, null, null, databaseChangeLog)
        change1 = new CreateTableChange()
        change1.addColumn(new ColumnConfig(new Column("column1")))
        change2 = new RawSQLChange("ALTER TABLE pendingEmails ALTER COLUMN createdAt datetime2 NOT NULL;")
        change2.setDbms("mssql")
        changeSet.addChange(change2)
        changeSet.addChange(change1)
        databaseChangeLog.addChangeSet(changeSet)
        listener = new DefaultChangeExecListener()

        then:
        changeSet.execute(databaseChangeLog, listener, new MockDatabase()) == ChangeSet.ExecType.EXECUTED
        listener.getDeployedChanges(changeSet).size() == 1

        when:
        databaseChangeLog = new DatabaseChangeLog("com/example/test.xml")

        changeSet =
                new ChangeSet("testId", "testAuthor", false, false, null, null, null, databaseChangeLog)
        change1 = new CreateTableChange()
        change1.addColumn(new ColumnConfig(new Column("column1")))
        change2 = new RawSQLChange("ALTER TABLE pendingEmails ALTER COLUMN createdAt datetime2 NOT NULL;")
        change2.setDbms("mssql")
        changeSet.addChange(change2)
        changeSet.addChange(change1)
        databaseChangeLog.addChangeSet(changeSet)
        listener = new DefaultChangeExecListener()

        then:
        changeSet.execute(databaseChangeLog, listener, new MockDatabase()) == ChangeSet.ExecType.EXECUTED
        listener.getDeployedChanges(changeSet).size() == 1

    }

    def "execute returns a MARK_RAN state when changeSet is set with validation failed as true"() {
        when:
        DatabaseChangeLog databaseChangeLog = new DatabaseChangeLog("com/example/test.xml")
        def changeSet = new ChangeSet(databaseChangeLog)
        def node = new ParsedNode(null, "changeSet").addChildren([id: "1", author: "mallod"])
        changeSet.setValidationFailed(true)

        then:
        changeSet.execute(databaseChangeLog, new MockDatabase()) == ChangeSet.ExecType.MARK_RAN
    }

    def "execute returns a EXECUTED state when changeSet is set with validation failed as false"() {
        when:
        DatabaseChangeLog databaseChangeLog = new DatabaseChangeLog("com/example/test.xml")
        def changeSet = new ChangeSet(databaseChangeLog)
        def node = new ParsedNode(null, "changeSet").addChildren([id: "2", author: "mallod"])
        changeSet.setValidationFailed(false)

        then:
        changeSet.execute(databaseChangeLog, new MockDatabase()) == ChangeSet.ExecType.EXECUTED
    }

    def "execute does not execute shell command against when adding MDC"() {
        when:
        DatabaseChangeLog databaseChangeLog = new DatabaseChangeLog("com/example/test.xml")
        def changeSet = new ChangeSet(databaseChangeLog)
        ExecuteShellCommandChange executeShellCommandChange = new ExecuteShellCommandChange()
        executeShellCommandChange.setExecutable("/bin/bash")
        executeShellCommandChange.addArg("-c")
        executeShellCommandChange.addArg("echo hi >> liquibase_shell_out")
        changeSet.addChange(executeShellCommandChange)

        then:
        changeSet.execute(databaseChangeLog, new MockDatabase()) == ChangeSet.ExecType.EXECUTED
        ! new File("liquibase_shell_out").exists()

        cleanup:
        new File("liquibase_shell_out").delete()
    }

    def "validate rollback serialization doesn't duplicate rollback key on a YAML changelog format"() {
        when:
        DatabaseChangeLog changeLog = new DatabaseChangeLog()
        ChangeSet changeSet = new ChangeSet(UUID.randomUUID().toString(), "author", false, false, "", "test", "mysql", changeLog)
        changeSet.addRollbackChange(new EmptyChange())
        changeLog.addChangeSet(changeSet)

        YamlChangeLogSerializer serializer = new YamlChangeLogSerializer()
        OutputStream outputStream = new FileOutputStream("changelog-with-rollback.yaml")
        serializer.write(changeLog.getChangeSets(), outputStream)

        then:
        def outputFile = new File("changelog-with-rollback.yaml")
        def contents = FileUtil.getContents(outputFile).replace("\n","").replace("\r","").trim()
        def expectKeys = "rollback:      empty: {}".trim()
        contents.contains(expectKeys)

        cleanup:
        outputStream.close()
        outputFile.delete()
    }

    def "validate rollback serialization doesn't duplicate rollback key on a XML changelog format"() {
        when:
        DatabaseChangeLog changeLog = new DatabaseChangeLog()
        ChangeSet changeSet = new ChangeSet(UUID.randomUUID().toString(), "author", false, false, "", "test", "mysql", changeLog)
        changeSet.addRollbackChange(new EmptyChange())
        changeLog.addChangeSet(changeSet)

        XMLChangeLogSerializer serializer = new XMLChangeLogSerializer()
        OutputStream outputStream = new FileOutputStream("changelog-with-rollback.xml")
        serializer.write(changeLog.getChangeSets(), outputStream)

        then:
        def outputFile = new File("changelog-with-rollback.xml")
        def contents = FileUtil.getContents(outputFile).replace("\n","").replace("\r","").trim()
        def expectedKeys = "<rollback>            <empty/>        </rollback>".trim()
        contents.contains(expectedKeys)

        cleanup:
        outputStream.close()
        outputFile.delete()
    }

    def "validate rollback serialization works as expected when having to rollback a SQLFile change"() {
        when:
        DatabaseChangeLog changeLog = new DatabaseChangeLog()
        def changeSet = new ChangeSet(new DatabaseChangeLog("com/example/test.xml"))
        def node = new ParsedNode(null, "changeSet").addChildren([id: "1", author: "nvoxland"])
        changeSet.load(node, resourceSupplier.simpleResourceAccessor)
        def sqlFileChange = new SQLFileChange()
        sqlFileChange.setPath("test/rollbackFile.sql")
        changeSet.addRollbackChange(sqlFileChange)
        changeLog.addChangeSet(changeSet)

        YamlChangeLogSerializer serializer = new YamlChangeLogSerializer()
        OutputStream outputStream = new FileOutputStream("changelog-with-rollback.yaml")
        serializer.write(changeLog.getChangeSets(), outputStream)

        then:
        def outputFile = new File("changelog-with-rollback.yaml")
        def contents = FileUtil.getContents(outputFile).replace("\n","").replace("\r","").trim()
        def expectedKeys = "rollback:      sqlFile:        path: test/rollbackFile.sql        splitStatements: true        stripComments: false".trim()
        contents.contains(expectedKeys)

        cleanup:
        outputStream.close()
        outputFile.delete()
    }

}
