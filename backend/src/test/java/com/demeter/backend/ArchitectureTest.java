package com.demeter.backend;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    private final JavaClasses productionClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.demeter.backend");

    @Test
    void controllersCannotBypassApplicationServices() {
        noClasses()
                .that().resideInAPackage("..api..")
                .and().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat()
                .resideInAPackage("..infrastructure..")
                .because("HTTP controllers must enter the application layer and its business chains")
                .check(productionClasses);
    }

    @Test
    void repositoriesRemainInfrastructureConcerns() {
        classes()
                .that().haveSimpleNameEndingWith("Repository")
                .should().resideInAPackage("..infrastructure..")
                .because("repository access must remain behind application services")
                .check(productionClasses);
    }

    @Test
    void domainObjectsDoNotDependOnOuterLayers() {
        noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..api..", "..application..", "..infrastructure..")
                .because("domain behavior must remain independent of delivery and persistence concerns")
                .check(productionClasses);
    }
}
