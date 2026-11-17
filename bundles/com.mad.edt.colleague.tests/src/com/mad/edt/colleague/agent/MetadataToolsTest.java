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

    /** tool names the suite calls over and over */
    private static final String FIND = "find_metadata";
    private static final String READ = "read_metadata";
    /** XML declaration that opens every fixture file */
    private static final String XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";
    /** the open and close tags around an object, attribute or section name */
    private static final String NAME_OPEN = "  <name>";
    private static final String NAME_CLOSE = "</name>\n";
    /** fixture paths spelled out in write calls and in expected results */
    private static final String BANK_FILE = "Catalogs/Банк/Банк.mdo";
    private static final String ANKETA_FILE = "Documents/Анкета/Анкета.mdo";
    /** repeated attribute types of the fixture */
    private static final String TYPE_STRING = "String";
    private static final String TYPE_NUMBER = "Number";
    /** the currency catalog the read tests lean on */
    private static final String CURRENCIES = "Валюты";
    private static final String CURRENCY_PRESENTATION = "Валюта";
    /** the tabular section of the questionnaire fixture */
    private static final String TOWNS = "Товары";

    @TempDir
    private Path ourDir;

    /** Root of the fixture project's metadata tree, the "src" folder of EDT. */
    private Path ourSrc;

    @BeforeEach
    void buildFixture() throws java.io.IOException {
        ourSrc = ourDir.resolve("Demo").resolve("src");
        write("Catalogs/" + CURRENCIES + "/" + CURRENCIES + ".mdo",
            catalog(CURRENCIES, CURRENCY_PRESENTATION, "Currencies",
                attribute("Код", TYPE_STRING, "9"), attribute("Наименование", TYPE_STRING, "150")));
        write(BANK_FILE, catalog("Банк", "Банк", "Bank"));
        write("Catalogs/Банки/Банки.mdo", catalog("Банки", "Банк", "Banks"));
        write("InformationRegisters/КурсыВалют/КурсыВалют.mdo", register("КурсыВалют",
            "Курсы валют", "Exchange rates", dimension(CURRENCY_PRESENTATION, "CatalogRef.Валюты"),
            resource("Курс", TYPE_NUMBER, "15", "4"), resource("Кратность", TYPE_NUMBER, "10", "0")));
        write(ANKETA_FILE, document("Анкета", "Анкета", "Questionnaire",
            attribute("Организация", "CatalogRef.Организации"),
            tabularSection(TOWNS, TOWNS, attribute("Номенклатура", "CatalogRef.Номенклатура"),
                attribute("Количество", TYPE_NUMBER, "15", "3"))));
        // form descriptors live next to the object file and are not objects
        write("Documents/Анкета/Анкета.form.mdo", XML_HEAD
            + "<form:Form xmlns:form=\"http://g5.1c.ru/v8/dt/form\"><name>Форма</name></form:Form>\n");
        write("Configuration/Configuration.mdo", XML_HEAD
            + "<mdclass:Configuration xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\">\n"
            + NAME_OPEN + "Демо" + NAME_CLOSE + "</mdclass:Configuration>\n");
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
        assertEquals(FIND, offered.get(0).getName());
        assertEquals(READ, offered.get(1).getName());
        for(final IToolExecutor tool : offered) {
            assertFalse(tool.getSpec().getDescription().isBlank());
            assertTrue(tool.isParallelSafe(), tool.getName() + " only reads metadata");
        }
    }

    @Test
    void findsObjectByName() throws Exception {
        final String found = tool(FIND).execute("{\"query\":\"Валюты\"}");

        assertTrue(found.contains("Catalog"), "kind of the object: " + found);
        assertTrue(found.contains("Demo/src/Catalogs/Валюты/Валюты.mdo"), found);
        assertTrue(found.contains(CURRENCY_PRESENTATION), "russian presentation: " + found);
    }

    @Test
    void findsCaseInsensitively() throws Exception {
        final String found = tool(FIND).execute("{\"query\":\"валюты\"}");

        assertTrue(found.contains("Demo/src/Catalogs/Валюты/Валюты.mdo"), found);
    }

    @Test
    void ranksTheExactNameFirst() throws Exception {
        final String found = tool(FIND).execute("{\"query\":\"Банк\"}");

        final int exact = found.indexOf(BANK_FILE);
        final int longer = found.indexOf("Catalogs/Банки/Банки.mdo");
        assertTrue(exact >= 0, found);
        assertTrue(longer >= 0, found);
        assertTrue(exact < longer, "exact name must come first: " + found);
    }

    @Test
    void filtersByKind() throws Exception {
        final String found = tool(FIND)
            .execute("{\"query\":\"Валют\",\"kind\":\"InformationRegister\"}");

        assertTrue(found.contains("InformationRegister"), found);
        assertTrue(found.contains("InformationRegisters/КурсыВалют/КурсыВалют.mdo"), found);
        assertFalse(found.contains("Catalogs/Валюты"), "catalogs filtered out: " + found);
    }

    @Test
    void reportsWhenNothingMatches() throws Exception {
        final String found = tool(FIND).execute("{\"query\":\"НетТакого\"}");

        assertTrue(found.startsWith("No metadata objects match"), found);
    }

    @Test
    void readsAttributesOfAnObject() throws Exception {
        final String read = tool(READ)
            .execute("{\"path\":\"Demo/src/Catalogs/Валюты/Валюты.mdo\"}");

        assertTrue(read.contains("Catalog Валюты"), read);
        assertTrue(read.contains("Код"), read);
        assertTrue(read.contains("String(9)"), read);
        assertTrue(read.contains("Наименование"), read);
        assertTrue(read.contains("String(150)"), read);
    }

    @Test
    void readsTabularSectionsWithTheirOwnAttributes() throws Exception {
        final String read = tool(READ).execute("{\"name\":\"Анкета\"}");

        assertTrue(read.contains("Tabular sections (1)"), read);
        assertTrue(read.contains(TOWNS), read);
        assertTrue(read.contains("Номенклатура"), read);
        assertTrue(read.contains("Number(15,3)"), read);
    }

    @Test
    void listsRegistersWithDimensions() throws Exception {
        final String read = tool(READ)
            .execute("{\"name\":\"КурсыВалют\",\"kind\":\"InformationRegister\"}");

        assertTrue(read.contains("Dimensions (1)"), read);
        assertTrue(read.contains(CURRENCY_PRESENTATION), read);
        assertTrue(read.contains("CatalogRef.Валюты"), read);
        assertTrue(read.contains("Resources (2)"), read);
        assertTrue(read.contains("Курс"), read);
        assertTrue(read.contains("Number(15,4)"), read);
    }

    @Test
    void ignoresFormDescriptorsWhenSearching() throws Exception {
        final String found = tool(FIND).execute("{\"query\":\"Анкета\"}");

        assertTrue(found.contains(ANKETA_FILE), found);
        assertFalse(found.contains(".form.mdo"), "form descriptors are not objects: " + found);
    }

    @Test
    void keepsTheResultUnderTheSharedToolBudget() throws Exception {
        final StringBuilder huge = new StringBuilder(catalog("Огромный", "Огромный", "Huge"));
        int at = huge.indexOf("</mdclass:Catalog>");
        for(int index = 0; index < 300; index++) {
            huge.insert(at, attribute("Реквизит" + index, TYPE_STRING, "50"));
        }
        write("Catalogs/Огромный/Огромный.mdo", huge.toString());

        final String read = tool(READ).execute("{\"path\":\"Demo/src/Catalogs/Огромный/Огромный.mdo\"}");

        assertTrue(read.length() < 4_000, "result stays under the agent budget: " + read.length());
        assertTrue(read.contains("more"), "the cut is announced: " + read);
    }

    @Test
    void refusesPathsOutsideTheMetadataRoots() throws Exception {
        final String refused = tool(READ)
            .execute("{\"path\":\"Demo/src/../../secret.mdo\"}");

        assertTrue(refused.startsWith("ERROR"), refused);
    }

    @Test
    void reportsANonMetadataFileAsError() throws Exception {
        write(ANKETA_FILE, "");

        final String read = tool(READ).execute("{\"path\":\"Demo/src/Documents/Анкета/Анкета.mdo\"}");

        assertTrue(read.startsWith("ERROR"), read);
    }

    @Test
    void toleratesATruncatedMetadataFile() throws Exception {
        write(BANK_FILE, XML_HEAD
            + "<mdclass:Catalog xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\">\n"
            + "  <name>Банк</name>\n  <attributes uuid=\"1\">\n    <name>Код</name>\n");

        final String read = tool(READ).execute("{\"path\":\"Demo/src/Catalogs/Банк/Банк.mdo\"}");

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
        return XML_HEAD
            + "<mdclass:Catalog xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" uuid=\"c\">\n"
            + "  <producedTypes>\n    <objectType typeId=\"t\" valueTypeId=\"v\"/>\n  </producedTypes>\n"
            + NAME_OPEN + theName + NAME_CLOSE
            + synonym(thePresented, theEnglish)
            + String.join("", theAttributes)
            + "</mdclass:Catalog>\n";
    }

    private static String document(final String theName, final String thePresented,
        final String theEnglish, final String... theAttributes) {
        return XML_HEAD
            + "<mdclass:Document xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" uuid=\"d\">\n"
            + NAME_OPEN + theName + NAME_CLOSE
            + synonym(thePresented, theEnglish)
            + String.join("", theAttributes)
            + "</mdclass:Document>\n";
    }

    private static String register(final String theName, final String thePresented,
        final String theEnglish, final String... theChildren) {
        return XML_HEAD
            + "<mdclass:InformationRegister xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\""
            + " uuid=\"r\">\n"
            + NAME_OPEN + theName + NAME_CLOSE
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
        return "  <attributes uuid=\"a\">\n    <name>" + theName + NAME_CLOSE
            + type(theType, theQualifiers) + "  </attributes>\n";
    }

    private static String dimension(final String theName, final String theType) {
        return "  <dimensions uuid=\"m\">\n    <name>" + theName + NAME_CLOSE
            + type(theType) + "  </dimensions>\n";
    }

    private static String resource(final String theName, final String theType,
        final String... theQualifiers) {
        return "  <resources uuid=\"s\">\n    <name>" + theName + NAME_CLOSE
            + type(theType, theQualifiers) + "  </resources>\n";
    }

    private static String tabularSection(final String theName, final String thePresented,
        final String... theAttributes) {
        return "  <tabularSections uuid=\"ts\">\n"
            + "    <producedTypes>\n      <rowType typeId=\"t\" valueTypeId=\"v\"/>\n    </producedTypes>\n"
            + "    <name>" + theName + NAME_CLOSE
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
