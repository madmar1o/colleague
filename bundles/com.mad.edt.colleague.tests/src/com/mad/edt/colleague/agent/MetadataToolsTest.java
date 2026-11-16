package com.mad.edt.colleague.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the two metadata tools the agent gets instead of reading raw .mdo
 * files: locating an object by name and optional kind without loading the
 * whole configuration tree, and reading one object back as a compact picture
 * of its attributes, tabular sections, dimensions and resources. The fixture
 * copies the real EDT export layout on purpose, so the tests exercise the
 * same path shapes the workspace produces.
 */
class MetadataToolsTest {

    @TempDir
    private Path ourDir;

    /** Root of the fixture project's metadata tree, the "src" folder of EDT. */
    private Path ourSrc;

    @BeforeEach
    void buildFixture() throws java.io.IOException {
        ourSrc = ourDir.resolve("Demo").resolve("src");
        write("Catalogs/Валюты/Валюты.mdo", catalog("Валюты", "Валюта", "Currencies",
            attribute("Код", "String", "9"), attribute("Наименование", "String", "150")));
        write("Catalogs/Банк/Банк.mdo", catalog("Банк", "Банк", "Bank"));
        write("Catalogs/Банки/Банки.mdo", catalog("Банки", "Банк", "Banks"));
        write("InformationRegisters/КурсыВалют/КурсыВалют.mdo", register("КурсыВалют",
            "Курсы валют", "Exchange rates", dimension("Валюта", "CatalogRef.Валюты"),
            resource("Курс", "Number", "15", "4"), resource("Кратность", "Number", "10", "0")));
        write("Documents/Анкета/Анкета.mdo", document("Анкета", "Анкета", "Questionnaire",
            attribute("Организация", "CatalogRef.Организации"),
            tabularSection("Товары", "Товары", attribute("Номенклатура", "CatalogRef.Номенклатура"),
                attribute("Количество", "Number", "15", "3"))));
        // form descriptors live next to the object file and are not objects
        write("Documents/Анкета/Анкета.form.mdo", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<form:Form xmlns:form=\"http://g5.1c.ru/v8/dt/form\"><name>Форма</name></form:Form>\n");
        write("Configuration/Configuration.mdo", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<mdclass:Configuration xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\">\n"
            + "  <name>Демо</name>\n</mdclass:Configuration>\n");
    }

    private MetadataTools tools() {
        return new MetadataTools(() -> List.of(new MetadataTools.Root("Demo", ourSrc)));
    }

    private IToolExecutor tool(final String theName) {
        for(final IToolExecutor tool : tools().tools()) {
            if(tool.getName().equals(theName)) {
                return tool;
            }
        }
        throw new IllegalStateException("no tool named " + theName);
    }

    @Test
    void advertisesBothToolsAsParallelSafeReaders() throws Exception {
        final List<IToolExecutor> offered = tools().tools();

        assertEquals(2, offered.size());
        assertEquals("find_metadata", offered.get(0).getName());
        assertEquals("read_metadata", offered.get(1).getName());
        for(final IToolExecutor tool : offered) {
            assertFalse(tool.getSpec().getDescription().isBlank());
            assertTrue(tool.isParallelSafe(), tool.getName() + " only reads metadata");
        }
    }

    @Test
    void findsObjectByName() throws Exception {
        final String found = tool("find_metadata").execute("{\"query\":\"Валюты\"}");

        assertTrue(found.contains("Catalog"), "kind of the object: " + found);
        assertTrue(found.contains("Demo/src/Catalogs/Валюты/Валюты.mdo"), found);
        assertTrue(found.contains("Валюта"), "russian presentation: " + found);
    }

    @Test
    void findsCaseInsensitively() throws Exception {
        final String found = tool("find_metadata").execute("{\"query\":\"валюты\"}");

        assertTrue(found.contains("Demo/src/Catalogs/Валюты/Валюты.mdo"), found);
    }

    @Test
    void ranksTheExactNameFirst() throws Exception {
        final String found = tool("find_metadata").execute("{\"query\":\"Банк\"}");

        final int exact = found.indexOf("Catalogs/Банк/Банк.mdo");
        final int longer = found.indexOf("Catalogs/Банки/Банки.mdo");
        assertTrue(exact >= 0, found);
        assertTrue(longer >= 0, found);
        assertTrue(exact < longer, "exact name must come first: " + found);
    }

    @Test
    void filtersByKind() throws Exception {
        final String found = tool("find_metadata")
            .execute("{\"query\":\"Валют\",\"kind\":\"InformationRegister\"}");

        assertTrue(found.contains("InformationRegister"), found);
        assertTrue(found.contains("InformationRegisters/КурсыВалют/КурсыВалют.mdo"), found);
        assertFalse(found.contains("Catalogs/Валюты"), "catalogs filtered out: " + found);
    }

    @Test
    void reportsWhenNothingMatches() throws Exception {
        final String found = tool("find_metadata").execute("{\"query\":\"НетТакого\"}");

        assertTrue(found.startsWith("No metadata objects match"), found);
    }

    @Test
    void readsAttributesOfAnObject() throws Exception {
        final String read = tool("read_metadata")
            .execute("{\"path\":\"Demo/src/Catalogs/Валюты/Валюты.mdo\"}");

        assertTrue(read.contains("Catalog Валюты"), read);
        assertTrue(read.contains("Код"), read);
        assertTrue(read.contains("String(9)"), read);
        assertTrue(read.contains("Наименование"), read);
        assertTrue(read.contains("String(150)"), read);
    }

    @Test
    void readsTabularSectionsWithTheirOwnAttributes() throws Exception {
        final String read = tool("read_metadata").execute("{\"name\":\"Анкета\"}");

        assertTrue(read.contains("Tabular sections (1)"), read);
        assertTrue(read.contains("Товары"), read);
        assertTrue(read.contains("Номенклатура"), read);
        assertTrue(read.contains("Number(15,3)"), read);
    }

    @Test
    void listsRegistersWithDimensions() throws Exception {
        final String read = tool("read_metadata")
            .execute("{\"name\":\"КурсыВалют\",\"kind\":\"InformationRegister\"}");

        assertTrue(read.contains("Dimensions (1)"), read);
        assertTrue(read.contains("Валюта"), read);
        assertTrue(read.contains("CatalogRef.Валюты"), read);
        assertTrue(read.contains("Resources (2)"), read);
        assertTrue(read.contains("Курс"), read);
        assertTrue(read.contains("Number(15,4)"), read);
    }

    @Test
    void ignoresFormDescriptorsWhenSearching() throws Exception {
        final String found = tool("find_metadata").execute("{\"query\":\"Анкета\"}");

        assertTrue(found.contains("Documents/Анкета/Анкета.mdo"), found);
        assertFalse(found.contains(".form.mdo"), "form descriptors are not objects: " + found);
    }

    @Test
    void keepsTheResultUnderTheSharedToolBudget() throws Exception {
        final StringBuilder huge = new StringBuilder(catalog("Огромный", "Огромный", "Huge"));
        int at = huge.indexOf("</mdclass:Catalog>");
        for(int index = 0; index < 300; index++) {
            huge.insert(at, attribute("Реквизит" + index, "String", "50"));
        }
        write("Catalogs/Огромный/Огромный.mdo", huge.toString());

        final String read = tool("read_metadata").execute("{\"path\":\"Demo/src/Catalogs/Огромный/Огромный.mdo\"}");

        assertTrue(read.length() < 4_000, "result stays under the agent budget: " + read.length());
        assertTrue(read.contains("more"), "the cut is announced: " + read);
    }

    @Test
    void refusesPathsOutsideTheMetadataRoots() throws Exception {
        final String refused = tool("read_metadata")
            .execute("{\"path\":\"Demo/src/../../secret.mdo\"}");

        assertTrue(refused.startsWith("ERROR"), refused);
    }

    @Test
    void reportsANonMetadataFileAsError() throws Exception {
        write("Documents/Анкета/Анкета.mdo", "");

        final String read = tool("read_metadata").execute("{\"path\":\"Demo/src/Documents/Анкета/Анкета.mdo\"}");

        assertTrue(read.startsWith("ERROR"), read);
    }

    @Test
    void toleratesATruncatedMetadataFile() throws Exception {
        write("Catalogs/Банк/Банк.mdo", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<mdclass:Catalog xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\">\n"
            + "  <name>Банк</name>\n  <attributes uuid=\"1\">\n    <name>Код</name>\n");

        final String read = tool("read_metadata").execute("{\"path\":\"Demo/src/Catalogs/Банк/Банк.mdo\"}");

        assertTrue(read.contains("Банк"), read);
        assertTrue(read.contains("incomplete"), "the cut file is reported honestly: " + read);
    }

    private void write(final String theRelativePath, final String theContent) throws IOException {
        final Path file = ourSrc.resolve(theRelativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, theContent, StandardCharsets.UTF_8);
    }

    private static String catalog(final String theName, final String thePresented,
        final String theEnglish, final String... theAttributes) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<mdclass:Catalog xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" uuid=\"c\">\n"
            + "  <producedTypes>\n    <objectType typeId=\"t\" valueTypeId=\"v\"/>\n  </producedTypes>\n"
            + "  <name>" + theName + "</name>\n"
            + synonym(thePresented, theEnglish)
            + String.join("", theAttributes)
            + "</mdclass:Catalog>\n";
    }

    private static String document(final String theName, final String thePresented,
        final String theEnglish, final String... theAttributes) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<mdclass:Document xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" uuid=\"d\">\n"
            + "  <name>" + theName + "</name>\n"
            + synonym(thePresented, theEnglish)
            + String.join("", theAttributes)
            + "</mdclass:Document>\n";
    }

    private static String register(final String theName, final String thePresented,
        final String theEnglish, final String... theChildren) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<mdclass:InformationRegister xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\""
            + " uuid=\"r\">\n"
            + "  <name>" + theName + "</name>\n"
            + synonym(thePresented, theEnglish)
            + String.join("", theChildren)
            + "</mdclass:InformationRegister>\n";
    }

    private static String synonym(final String theRussian, final String theEnglish) {
        return "  <synonym>\n    <key>ru</key>\n    <value>" + theRussian + "</value>\n  </synonym>\n"
            + "  <synonym>\n    <key>en</key>\n    <value>" + theEnglish + "</value>\n  </synonym>\n";
    }

    private static String attribute(final String theName, final String theType,
        final String... theQualifiers) {
        return "  <attributes uuid=\"a\">\n    <name>" + theName + "</name>\n"
            + type(theType, theQualifiers) + "  </attributes>\n";
    }

    private static String dimension(final String theName, final String theType) {
        return "  <dimensions uuid=\"m\">\n    <name>" + theName + "</name>\n"
            + type(theType) + "  </dimensions>\n";
    }

    private static String resource(final String theName, final String theType,
        final String... theQualifiers) {
        return "  <resources uuid=\"s\">\n    <name>" + theName + "</name>\n"
            + type(theType, theQualifiers) + "  </resources>\n";
    }

    private static String tabularSection(final String theName, final String thePresented,
        final String... theAttributes) {
        return "  <tabularSections uuid=\"ts\">\n"
            + "    <producedTypes>\n      <rowType typeId=\"t\" valueTypeId=\"v\"/>\n    </producedTypes>\n"
            + "    <name>" + theName + "</name>\n"
            + synonym(thePresented, thePresented)
            + String.join("", theAttributes)
            + "  </tabularSections>\n";
    }

    private static String type(final String theType, final String... theQualifiers) {
        String qualifiers = "";
        if(theQualifiers.length == 1) {
            qualifiers = "      <stringQualifiers>\n        <length>" + theQualifiers[0]
                + "</length>\n      </stringQualifiers>\n";
        } else if(theQualifiers.length == 2) {
            qualifiers = "      <numberQualifiers>\n        <precision>" + theQualifiers[0]
                + "</precision>\n        <scale>" + theQualifiers[1] + "</scale>\n      </numberQualifiers>\n";
        }
        return "    <type>\n      <types>" + theType + "</types>\n" + qualifiers + "    </type>\n";
    }
}
