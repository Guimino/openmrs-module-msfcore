/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 * 
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.msfcore;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.GlobalProperty;
import org.openmrs.PatientIdentifierType;
import org.openmrs.api.context.Context;
import org.openmrs.module.BaseModuleActivator;
import org.openmrs.module.appframework.service.AppFrameworkService;
import org.openmrs.module.emrapi.EmrApiConstants;
import org.openmrs.module.htmlformentry.HtmlFormEntryService;
import org.openmrs.module.htmlformentryui.HtmlFormUtil;
import org.openmrs.module.idgen.IdentifierSource;
import org.openmrs.module.idgen.service.IdentifierSourceService;
import org.openmrs.module.metadatadeploy.api.MetadataDeployService;
import org.openmrs.module.metadatamapping.MetadataTermMapping;
import org.openmrs.module.metadatamapping.api.MetadataMappingService;
import org.openmrs.module.msfcore.api.DHISService;
import org.openmrs.module.msfcore.api.MSFCoreService;
import org.openmrs.module.msfcore.metadata.MSFMetadataBundle;
import org.openmrs.module.msfcore.metadata.PatientIdentifierTypes;
import org.openmrs.module.referencemetadata.ReferenceMetadataConstants;
import org.openmrs.scheduler.TaskDefinition;

/**
 * This class contains the logic that is run every time this module is either
 * started or shutdown
 */
public class MSFCoreActivator extends BaseModuleActivator {

    private Log log = LogFactory.getLog(getClass());

    /**
     * @see #started()
     */
    public void started() {
        log.info("Started MSF Core Module");

        Context.getService(DHISService.class).transferDHISMappingsToDataDirectory();
        Context.getService(DHISService.class).installDHIS2Metadata();

        setGlobalPropertyDefaultLocale();
        installMSFMeta();

        // ensure 'Auto Close Visits Task' is started
        TaskDefinition autoCloseVisits = Context.getSchedulerService().getTaskByName(MSFCoreConfig.TASK_AUTO_CLOSE_VISIT);
        if (autoCloseVisits != null && !autoCloseVisits.getStartOnStartup()) {
            autoCloseVisits.setStartOnStartup(true);
            Context.getSchedulerService().saveTaskDefinition(autoCloseVisits);
        }
    }

    private void installMSFMeta() {
        log.info("Replacing default reference application registration app");
        Context.getService(AppFrameworkService.class).disableApp(MSFCoreConfig.REGISTRATION_APP_EXTENSION_ID);
        Context.getService(AppFrameworkService.class).enableApp(MSFCoreConfig.MSF_REGISTRATION_APP_EXTENSION_ID);

        // disable the default find patient app to provide one which allows searching for patients at the footer of the search for patients page
        Context.getService(AppFrameworkService.class).disableApp(MSFCoreConfig.SEARCH_APP_EXTENSION_ID);
        Context.getService(AppFrameworkService.class).enableApp(MSFCoreConfig.MSF_SEARCH_APP_EXTENSION_ID);

        log.info("Installing MSF metadata");
        Context.getService(MetadataDeployService.class).installBundle(Context.getRegisteredComponents(MSFMetadataBundle.class).get(0));

        log.info("Installation and configuration of default MSF Identifier");
        Context.getService(MSFCoreService.class).msfIdentifierGeneratorInstallation();

        log.info("Un requiring OpenMRS ID if not done");
        PatientIdentifierType openmrsIdType = Context.getPatientService().getPatientIdentifierTypeByName(
                        ReferenceMetadataConstants.OPENMRS_ID_NAME);
        if (openmrsIdType.getRequired()) {
            openmrsIdType.setRequired(false);
            Context.getPatientService().savePatientIdentifierType(openmrsIdType);
        }

        log.info("Configuting primary Identifier");
        MetadataMappingService metadataMappingService = Context.getService(MetadataMappingService.class);
        MetadataTermMapping primaryIdentifierTypeMapping = metadataMappingService.getMetadataTermMapping(
                        EmrApiConstants.EMR_METADATA_SOURCE_NAME, EmrApiConstants.PRIMARY_IDENTIFIER_TYPE);
        PatientIdentifierType msfIdType = Context.getPatientService().getPatientIdentifierTypeByUuid(
                        PatientIdentifierTypes.MSF_PRIMARY_ID_TYPE.uuid());
        if (!msfIdType.getUuid().equals(primaryIdentifierTypeMapping.getMetadataUuid())) {
            primaryIdentifierTypeMapping.setMappedObject(msfIdType);
            metadataMappingService.saveMetadataTermMapping(primaryIdentifierTypeMapping);
        }

        log.info("Setting primary identifier source");
        IdentifierSource msfIdSource = Context.getService(IdentifierSourceService.class).getIdentifierSourceByUuid(
                        MSFCoreConfig.PATIENT_ID_TYPE_SOURCE_UUID);
        if (msfIdSource != null) {
            Context.getAdministrationService().updateGlobalProperty(MSFCoreConfig.GP_OPENMRS_IDENTIFIER_SOURCE_ID,
                            String.valueOf(msfIdSource.getId()));
        }

        log.info("Installing MSF Forms");
        installMsfForms();
    }

    private void removeMSFMeta() {
        log.info("Enabling default reference application registration app");
        Context.getService(AppFrameworkService.class).disableApp(MSFCoreConfig.MSF_REGISTRATION_APP_EXTENSION_ID);
        Context.getService(AppFrameworkService.class).enableApp(MSFCoreConfig.REGISTRATION_APP_EXTENSION_ID);

        log.info("Requiring OpenMRS ID if not done");
        PatientIdentifierType openmrsIdType = Context.getPatientService().getPatientIdentifierTypeByName(
                        ReferenceMetadataConstants.OPENMRS_ID_NAME);
        PatientIdentifierType msfIdType = Context.getPatientService().getPatientIdentifierTypeByUuid(
                        PatientIdentifierTypes.MSF_PRIMARY_ID_TYPE.uuid());
        if (!openmrsIdType.getRequired()) {
            openmrsIdType.setRequired(true);
            Context.getPatientService().savePatientIdentifierType(openmrsIdType);
        }
        if (msfIdType.getRequired()) {
            msfIdType.setRequired(false);
            Context.getPatientService().savePatientIdentifierType(msfIdType);
        }

        log.info("Configuting primary Identifier");
        MetadataMappingService metadataMappingService = Context.getService(MetadataMappingService.class);
        MetadataTermMapping primaryIdentifierTypeMapping = metadataMappingService.getMetadataTermMapping(
                        EmrApiConstants.EMR_METADATA_SOURCE_NAME, EmrApiConstants.PRIMARY_IDENTIFIER_TYPE);
        if (!openmrsIdType.getUuid().equals(primaryIdentifierTypeMapping.getMetadataUuid())) {
            primaryIdentifierTypeMapping.setMappedObject(openmrsIdType);
            metadataMappingService.saveMetadataTermMapping(primaryIdentifierTypeMapping);
        }

        log.info("Setting primary identifier source");
        IdentifierSource sourceForPrimaryType = Context.getService(IdentifierSourceService.class).getAutoGenerationOption(openmrsIdType)
                        .getSource();
        if (sourceForPrimaryType != null) {
            Context.getAdministrationService().updateGlobalProperty(MSFCoreConfig.GP_OPENMRS_IDENTIFIER_SOURCE_ID,
                            String.valueOf(sourceForPrimaryType.getId()));
        }

        // disable the MSF find patient app and enable the default core apps one
        Context.getService(AppFrameworkService.class).enableApp(MSFCoreConfig.SEARCH_APP_EXTENSION_ID);
        Context.getService(AppFrameworkService.class).disableApp(MSFCoreConfig.MSF_SEARCH_APP_EXTENSION_ID);
    }

    private void setGlobalPropertyDefaultLocale() {
        log.info("Setting the Global Property default_locale ");
        GlobalProperty defaultLocale = Context.getAdministrationService().getGlobalPropertyObject(
                        MSFCoreConfig.GLOBAL_PROPERTY_DEFAULT_LOCALE_PROPERTY_NAME);
        defaultLocale.setPropertyValue(Context.getLocale().getLanguage().toLowerCase());
        Context.getAdministrationService().saveGlobalProperty(defaultLocale);
    }

    /**
     * @see #shutdown()
     */
    public void shutdown() {
        log.info("Shutdown MSF Core");
    }

    @Override
    public void willStop() {
        removeMSFMeta();
        Context.getAdministrationService().updateGlobalProperty(MSFCoreConfig.GP_MANADATORY, "false");
        super.willStop();
    }

    @Override
    public void willStart() {
        Context.getAdministrationService().updateGlobalProperty(MSFCoreConfig.GP_MANADATORY, "true");
        super.willStart();
    }

    /**
     * Installing MSF Forms
     */
    private void installMsfForms() {
        try {
            for (File form : getFormsResourceFiles()) {
                HtmlFormUtil.getHtmlFormFromResourceXml(Context.getFormService(), Context.getService(HtmlFormEntryService.class),
                                new Scanner(form).useDelimiter("\\Z").next());
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    private File[] getFormsResourceFiles() {
        List<File> formXmls = new ArrayList<File>();
        for (File file : new File(getClass().getClassLoader().getResource("htmlforms").getPath()).listFiles()) {
            if (file.exists() && file.getName().endsWith("Form.xml")) {
                formXmls.add(file.getAbsoluteFile());
            }
        }
        return formXmls.toArray(new File[formXmls.size()]);
    }

}
