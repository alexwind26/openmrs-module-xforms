package org.openmrs.module.xforms;

import java.io.File;
import java.io.FileWriter;
import java.util.Date;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Location;
import org.openmrs.Patient;
import org.openmrs.PatientIdentifier;
import org.openmrs.PatientIdentifierType;
import org.openmrs.PersonAttribute;
import org.openmrs.PersonAttributeType;
import org.openmrs.PersonName;
import org.openmrs.User;
import org.openmrs.api.APIException;
import org.openmrs.api.PatientService;
import org.openmrs.api.PersonService;
import org.openmrs.api.context.Context;
import org.openmrs.module.xforms.formentry.FormEntryWrapper;
import org.openmrs.module.xforms.model.PersonRepeatAttribute;
import org.openmrs.module.xforms.util.DOMUtil;
import org.openmrs.module.xforms.util.XformsUtil;
import org.openmrs.util.OpenmrsUtil;
import org.openmrs.util.OpenmrsConstants.PERSON_TYPE;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;


/**
 * Processes Xforms Queue entries.
 * When the processing is successful, the queue entry is submitted to the FormEntry Queue.
 * For unsuccessful processing, the queue entry is put in the Xforms error folder.
 * 
 * @author Daniel Kayiwa
 * @version 1.0
 */
@Transactional
public class XformsQueueProcessor {

	private static final Log log = LogFactory.getLog(XformsQueueProcessor.class);
	private static Boolean isRunning = false; // allow only one running
	private static final DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();

	public XformsQueueProcessor(){

	}

	/**
	 * Starts up a thread to process all existing xforms queue entries
	 */
	public void processXformsQueue() throws APIException {
		synchronized (isRunning) {
			if (isRunning) {
				log.warn("XformsQueue processor aborting (another processor already running)");
				return;
			}
			isRunning = true;
		}
		try {			
			DocumentBuilder db = dbf.newDocumentBuilder();

			File queueDir = XformsUtil.getXformsQueueDir();
			for (File file : queueDir.listFiles()) 
				submitXForm(db,XformsUtil.readFile(file.getAbsolutePath()),file.getAbsolutePath());
		}
		catch(Exception e){
			log.error("Problem occured while processing Xforms queue", e);
		}
		finally {
			isRunning = false;
		}
	}

	private void submitXForm(DocumentBuilder db ,String xml, String pathName){
		String xmlOriginal = xml;
		try{	
			Document doc = db.parse(IOUtils.toInputStream(xml));
			Element root = doc.getDocumentElement();
			
			if(DOMUtil.isNewPatientDoc(doc)){
				if(saveNewPatient(root,getCreator(doc)) == null)
					saveFormInError(xml,pathName);
				else
					saveFormInArchive(xml,pathName);
			}
			else if(DOMUtil.isEncounterDoc(doc))
				submitXForm(doc,xml,pathName,true);
			else{
				Integer patientId = null;
				
				NodeList list = doc.getDocumentElement().getChildNodes();
				for(int index = 0; index < list.getLength(); index++){
					Node node = list.item(index);
					if(node.getNodeType() != Node.ELEMENT_NODE)
						continue;
					
					if(DOMUtil.isNewPatientElementDoc((Element)node)){
						patientId = saveNewPatient((Element)node,getCreator(doc));
						if(patientId == null){
							saveFormInError(xml,pathName);
							return;
						}	
					}
					else{
						setNewPatientId((Element)node,patientId);
						Document encounterDoc = createNewDocFromNode(db,(Element)node);
						xml = XformsUtil.doc2String(encounterDoc);
						submitXForm(encounterDoc,xml,pathName,false);
					}
				}
				
				saveFormInArchive(xmlOriginal,pathName);
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			saveFormInError(xmlOriginal,pathName);
		}
	}
	
	private Document createNewDocFromNode(DocumentBuilder db, Element element){
		Document doc = db.newDocument();
		doc.appendChild(doc.adoptNode(element));
		return doc;
	}

	/**
	 * Sets the patientid node to the value of the patient_id as got from the server.
	 * 
	 * @param root - the root element of the patientid node to set.
	 * @return - true if set, else false.
	 */
	private void setNewPatientId(Element root, Integer patientId){
		try{
			NodeList elemList = root.getElementsByTagName(XformBuilder.NODE_PATIENT_PATIENT_ID);
			if (!(elemList != null && elemList.getLength() > 0)) 
				return;
			
			elemList.item(0).setTextContent(patientId.toString());
		}
		catch(Exception e){
			log.error(e.getMessage(),e);
		}
	}
	
	private void submitXForm(Document doc, String xml, String pathName, boolean archive){
		String xmlOriginal = xml;
		try{
			fillPatientIdIfMissing(doc);
			setMultipleSelectValues(doc.getDocumentElement());
			xml = XformsUtil.doc2String(doc);
			FormEntryWrapper.createFormEntryQueue(xml);
			
			if(archive)
				saveFormInArchive(xmlOriginal,pathName);

		} catch (Exception e) {
			log.error(e.getMessage(), e);
			saveFormInError(xmlOriginal,pathName);
		}
	}

	/**
	 * Achives a submitted form after processing.
	 * 
	 * @param xml - the form data.
	 * @param folder - the folder to save in.
	 * @param queuePathName - the path and name of this file in the queue. If you dont supply this,
	 * 						  a new radom file is created in this folder, else the a file with the
	 * 						  same name as the queued one is created in this folder.
	 */
	private String saveForm(String xml,File folder,String queuePathName){
		String pathName;// = folder.getAbsolutePath()+File.separatorChar+XformsUtil.getRandomFileName()+XformConstants.XML_FILE_EXTENSION;
		if(queuePathName == null)
			pathName = OpenmrsUtil.getOutFile(folder, new Date(), Context.getAuthenticatedUser()).getAbsolutePath();
		else
			pathName = folder.getAbsolutePath()+File.separatorChar+queuePathName.substring(queuePathName.lastIndexOf(File.separatorChar)+1);

		try{
			FileWriter writter = new FileWriter(pathName, false);
			writter.write(xml);
			writter.close();

			if(queuePathName != null){
				try{
					File file = new File(queuePathName);
					if(!file.delete())
						file.deleteOnExit();
				}catch(Exception e){
					log.error(e.getMessage(),e);
				}
			}
		}
		catch(Exception e){
			log.error(e.getMessage(),e);
		}

		return pathName;
	}

	/**
	 * Saves an xform in the xforms archive.
	 * 
	 * @param xml - the xml of the xform.
	 * @param queuePathName - the queue full path and file name of this xform.
	 * @return - the archive full path and file name.
	 */
	private String saveFormInArchive(String xml,String queuePathName){
		return saveForm(xml,XformsUtil.getXformsArchiveDir(new Date()),queuePathName);
	}

	/**
	 * Saves an xform in the errors folder.
	 * 
	 * @param xml - the xml of the xform.
	 * @param queuePathName - the queue full path and file name of this xform.
	 * @return - the error full path and file name.
	 */
	private String saveFormInError(String xml,String queuePathName){
		return saveForm(xml,XformsUtil.getXformsErrorDir(),queuePathName);
	}

	/** 
	 * Creates a new patient from an xform create new patient document.
	 * 
	 * @param doc - the document.
	 * @param creator - the logged on user.
	 * @return - true if the patient is created successfully, else false.
	 */
	private Integer saveNewPatient(Element root, User creator){		
		PatientService patientService = Context.getPatientService();
		XformsService xformsService = (XformsService)Context.getService(XformsService.class);

		Patient pt = new Patient();
		PersonName pn = new PersonName();
		pn.setGivenName(DOMUtil.getElementValue(root,XformBuilder.NODE_GIVEN_NAME));
		pn.setFamilyName(DOMUtil.getElementValue(root,XformBuilder.NODE_FAMILY_NAME));
		pn.setMiddleName(DOMUtil.getElementValue(root,XformBuilder.NODE_MIDDLE_NAME));

		pn.setCreator(creator);
		pn.setDateCreated(new Date());
		pt.addName(pn);

		String val = DOMUtil.getElementValue(root,XformBuilder.NODE_BIRTH_DATE);
		if(val != null && val.length() > 0)
			try{ pt.setBirthdate(XformsUtil.fromSubmitString2Date(val)); } catch(Exception e){log.error(val,e); }

			pt.setGender(DOMUtil.getElementValue(root,XformBuilder.NODE_GENDER));
			pt.setCreator(creator);
			pt.setDateCreated(new Date());		

			PatientIdentifier identifier = new PatientIdentifier();
			identifier.setCreator(creator);
			identifier.setDateCreated(new Date());
			identifier.setIdentifier(DOMUtil.getElementValue(root,XformBuilder.NODE_IDENTIFIER));
			int id = Integer.parseInt(DOMUtil.getElementValue(root,XformBuilder.NODE_IDENTIFIER_TYPE_ID));
			PatientIdentifierType identifierType = patientService.getPatientIdentifierType(id);
			identifier.setIdentifierType(identifierType);
			identifier.setLocation(getLocation(DOMUtil.getElementValue(root,XformBuilder.NODE_LOCATION_ID)));
			pt.addIdentifier(identifier);

			addPersonAttributes(pt,root,xformsService);

			Patient pt2 = patientService.identifierInUse(identifier.getIdentifier(),identifier.getIdentifierType(),pt);
			if(pt2 == null){
				pt = patientService.savePatient(pt);
				addPersonRepeatAttributes(pt,root,xformsService);
				return pt.getPatientId();
			}
			else if(rejectExistingPatientCreation()){
				log.error("Tried to create patient who already exists with the identifier:"+identifier.getIdentifier()+" REJECTED.");
				return null;
			}
			else{
				log.warn("Tried to create patient who already exists with the identifier:"+identifier.getIdentifier()+" ACCEPTED.");
				return pt.getPatientId();
			}
	}

	private void addPersonAttributes(Patient pt, Element root,XformsService xformsService){
		// look for person attributes in the xml doc and save to person
		PersonService personService = Context.getPersonService();
		for (PersonAttributeType type : personService.getPersonAttributeTypes(PERSON_TYPE.PERSON, null)) {
			NodeList nodes = root.getElementsByTagName("person_attribute"+type.getPersonAttributeTypeId());

			if(nodes == null || nodes.getLength() == 0)
				continue;

			String value = ((Element)nodes.item(0)).getTextContent();
			if(value == null || value.length() == 0)
				continue;

			pt.addAttribute(new PersonAttribute(type, value));
		}
	}

	private void addPersonRepeatAttributes(Patient pt, Element root,XformsService xformsService){
		NodeList nodes = root.getChildNodes();
		if(nodes == null)
			return;

		for(int index = 0; index < nodes.getLength(); index++){
			Node node = nodes.item(index);
			if(node.getNodeType() != Node.ELEMENT_NODE)
				continue;

			String name = node.getNodeName();
			if(name.startsWith("person_attribute_repeat_section")){
				String attributeId = name.substring("person_attribute_repeat_section".length());
				addPersonRepeatAttribute(pt,node,attributeId,xformsService);
			}
		}
	}

	private void addPersonRepeatAttribute(Patient pt,Node repeatNode,String attributeId,XformsService xformsService){
		NodeList nodes = repeatNode.getChildNodes();
		if(repeatNode == null)
			return;

		for(int index = 0; index < nodes.getLength(); index++){
			Node node = nodes.item(index);
			if(node.getNodeType() != Node.ELEMENT_NODE)
				continue;

			String name = node.getNodeName();
			if(name.startsWith("person_attribute"))		
				addPersonRepeatAttributeValues(pt,node,attributeId,xformsService,index+1);
		}
	}

	private void addPersonRepeatAttributeValues(Patient pt,Node repeatNode,String attributeId,XformsService xformsService, int displayOrder){
		NodeList nodes = repeatNode.getChildNodes();
		if(repeatNode == null)
			return;

		for(int index = 0; index < nodes.getLength(); index++){
			Node node = nodes.item(index);
			if(node.getNodeType() != Node.ELEMENT_NODE)
				continue;

			String name = node.getNodeName();
			if(name.startsWith("person_attribute_concept")){
				String valueId = name.substring("person_attribute_concept".length());

				PersonRepeatAttribute personRepeatAttribute = new PersonRepeatAttribute();
				personRepeatAttribute.setPersonId(pt.getPersonId());
				personRepeatAttribute.setCreator(Context.getAuthenticatedUser().getUserId());
				personRepeatAttribute.setDateCreated(new Date());
				personRepeatAttribute.setValue(node.getTextContent());
				personRepeatAttribute.setValueId(Integer.parseInt(valueId));
				personRepeatAttribute.setValueIdType(PersonRepeatAttribute.VALUE_ID_TYPE_CONCEPT);
				personRepeatAttribute.setValueDisplayOrder(displayOrder);
				personRepeatAttribute.setAttributeTypeId(Integer.parseInt(attributeId));

				xformsService.savePersonRepeatAttribute(personRepeatAttribute);
			}
		}
	}

	/**
	 * Check if we are to reject forms for patients considered new when they already exist, 
	 * by virture of patient identifier.
	 * @return true if we are to reject, else false.
	 */
	private boolean rejectExistingPatientCreation(){
		String reject = Context.getAdministrationService().getGlobalProperty(XformConstants.GLOBAL_PROP_KEY_REJECT_EXIST_PATIENT_CREATE,XformConstants.DEFAULT_REJECT_EXIST_PATIENT_CREATE);
		return !("false".equalsIgnoreCase(reject));
	}

	/**
	 * Gets a location object given a locaton id
	 * 
	 * @param locationId - the id.
	 * @return
	 */
	private Location getLocation(String locationId){
		return Context.getLocationService().getLocation(Integer.parseInt(locationId));
	}

	private User getCreator(Document doc){
		//return Context.getAuthenticatedUser();
		NodeList elemList = doc.getElementsByTagName(XformConstants.NODE_ENTERER);
		if (elemList != null && elemList.getLength() > 0) {
			String s = ((Element)elemList.item(0)).getTextContent();
			User user = Context.getUserService().getUser(Integer.valueOf(s.substring(0,s.indexOf('^'))));
			return user;
		}
		return null;
	}

	/**
	 * Converts xforms multiple select answer values to the format expected by
	 * the openmrs form model.
	 * 
	 * @param parentNode - the parent node of the document.
	 */
	private void setMultipleSelectValues(Node parentNode){
		NodeList nodes = parentNode.getChildNodes();
		for(int i=0; i<nodes.getLength(); i++){
			Node node = nodes.item(i);
			if(node.getNodeType() != Node.ELEMENT_NODE)
				continue;
			if(isMultipleSelectNode(node))
				setMultipleSelectNodeValues(node);
			setMultipleSelectValues(node);
		}
	}

	/** 
	 * Gets the values of a multiple select node.
	 * 
	 * @param parentNode- the node
	 * @return - a sting with values separated by space.
	 */
	private String getMultipleSelectNodeValue(Node parentNode){
		String value = null;

		NodeList nodes = parentNode.getChildNodes();
		for(int i=0; i<nodes.getLength(); i++){
			Node node = nodes.item(i);
			if(node.getNodeType() != Node.ELEMENT_NODE)
				continue;
			String name = node.getNodeName();
			if(name != null && name.equalsIgnoreCase(XformBuilder.NODE_XFORMS_VALUE)){
				value = node.getTextContent();
				parentNode.removeChild(node);
				break;
			}
		}

		return value;
	}

	/**
	 * Sets the values of an openmrs multiple select node.
	 * 
	 * @param parentNode - the node.
	 */
	private void setMultipleSelectNodeValues(Node parentNode){
		String values = getMultipleSelectNodeValue(parentNode);
		if(values == null || values.length() == 0)
			return;

		String[] valueArray = values.split(XformBuilder.MULTIPLE_SELECT_VALUE_SEPARATOR);

		NodeList nodes = parentNode.getChildNodes();
		for(int i=0; i<nodes.getLength(); i++){
			Node node = nodes.item(i);
			if(node.getNodeType() != Node.ELEMENT_NODE)
				continue;

			String name = node.getNodeName();
			if(name.equalsIgnoreCase(XformBuilder.NODE_DATE) || name.equalsIgnoreCase(XformBuilder.NODE_TIME) || 
					name.equalsIgnoreCase(XformBuilder.NODE_VALUE) || name.equalsIgnoreCase(XformBuilder.NODE_XFORMS_VALUE))
				continue;
			setMultipleSelectNodeValue(node,valueArray);
		}
	}

	/**
	 * Sets the value of an openmrs multiple select node.
	 * 
	 * @param node - the multiple select node.
	 * @param valueArray - an array of selected values.
	 */
	private void setMultipleSelectNodeValue(Node node,String[] valueArray){
		for(String value : valueArray){
			if(!value.equalsIgnoreCase(node.getNodeName()))
				continue;
			node.setTextContent(XformBuilder.VALUE_TRUE);
			return;
		}

		node.setTextContent(XformBuilder.VALUE_FALSE);
	}

	/**
	 * Checks if a node is multiple select.
	 * 
	 * @param node - the node to check.
	 * @return - true if it is a multiple select node, else false.
	 */
	private boolean isMultipleSelectNode(Node node){
		boolean multipSelect = false;

		NamedNodeMap attributes = node.getAttributes();
		if(attributes != null){
			Node multipleValue = attributes.getNamedItem(XformBuilder.ATTRIBUTE_MULTIPLE);
			if(attributes.getNamedItem(XformBuilder.ATTRIBUTE_OPENMRS_CONCEPT) != null &&  multipleValue != null && multipleValue.getNodeValue().equals("1"))
				multipSelect = true;
		}

		return multipSelect;
	}
	
	private void fillPatientIdIfMissing(Document doc) throws Exception{
		String patientid = DOMUtil.getElementValue(doc,XformBuilder.NODE_PATIENT_PATIENT_ID);
		if(patientid != null && patientid.trim().length() > 0)
			return; //patient id is properly filled. may need to check if the patient exists
		
		//Check if patient identifier is filled.
		String patientIdentifier = getPatientIdentifier(doc);;
		if(patientIdentifier == null || patientIdentifier.trim().length() == 0)
			throw new Exception("Expected patient identifier value");

		List<Patient> patients = Context.getPatientService().getPatients(null, patientIdentifier, null);
		if(patients != null && patients.size() > 1)
			throw new Exception("More than one patient was found with identifier " + patientIdentifier);

		if(patients != null && patients.size() == 1){
			DOMUtil.setElementValue(doc.getDocumentElement(), XformBuilder.NODE_PATIENT_PATIENT_ID, patients.get(0).getPatientId().toString());
			return;
		}

		//Check if patient identifier type is filled
		String identifierType = DOMUtil.getElementValue(doc, XformBuilder.NODE_PATIENT_IDENTIFIER_TYPE);
		if(identifierType == null || identifierType.trim().length() == 0)
			throw new Exception("Expected patient identifier type value");

		//Check if family name is filled.
		String familyName = DOMUtil.getElementValue(doc, XformBuilder.NODE_PATIENT_FAMILY_NAME);
		if(familyName == null || familyName.trim().length() == 0)
			throw new Exception("Expected patient family name value");

		//Check if gender is filled
		String gender = DOMUtil.getElementValue(doc, XformBuilder.NODE_PATIENT_GENDER);
		if(gender == null || gender.trim().length() == 0)
			throw new Exception("Expected patient gender value");

		//Check if birth date is filled
		String birthDate = DOMUtil.getElementValue(doc, XformBuilder.NODE_PATIENT_BIRTH_DATE);
		if(birthDate == null || birthDate.trim().length() == 0)
			throw new Exception("Expected patient birth date value");

		Patient patient = new Patient();
		patient.setCreator(getCreator(doc));
		patient.setDateCreated(new Date());	
		patient.setGender(gender);

		PersonName pn = new PersonName();

		pn.setFamilyName(familyName);
		pn.setGivenName(DOMUtil.getElementValue(doc, XformBuilder.NODE_PATIENT_GIVEN_NAME));
		pn.setMiddleName(DOMUtil.getElementValue(doc, XformBuilder.NODE_PATIENT_MIDDLE_NAME));

		pn.setCreator(patient.getCreator());
		pn.setDateCreated(patient.getDateCreated());
		patient.addName(pn);

		PatientIdentifier identifier = new PatientIdentifier();
		identifier.setCreator(patient.getCreator());
		identifier.setDateCreated(patient.getDateCreated());
		identifier.setIdentifier(patientIdentifier.toString());

		int id = Integer.parseInt(identifierType);
		PatientIdentifierType idtfType = Context.getPatientService().getPatientIdentifierType(id);

		identifier.setIdentifierType(idtfType);
		identifier.setLocation(getLocation(DOMUtil.getElementValue(doc, XformBuilder.NODE_ENCOUNTER_LOCATION_ID)));
		patient.addIdentifier(identifier);

		patient.setBirthdate(XformsUtil.fromSubmitString2Date(birthDate.toString()));

		Context.getPatientService().savePatient(patient);
		DOMUtil.setElementValue(doc.getDocumentElement(), XformBuilder.NODE_PATIENT_PATIENT_ID, patient.getPatientId().toString());
	}
	
	private String getPatientIdentifier(Document doc){
		NodeList elemList = doc.getDocumentElement().getElementsByTagName("patient");
		if (!(elemList != null && elemList.getLength() > 0))
			return null;
		
		Element patientNode = (Element)elemList.item(0);

		NodeList children = patientNode.getChildNodes();
		int len = patientNode.getChildNodes().getLength();
		for(int index=0; index<len; index++){
			Node child = children.item(index);
			if(child.getNodeType() != Node.ELEMENT_NODE)
				continue;
			
			if("patient_identifier".equalsIgnoreCase(((Element)child).getAttribute("openmrs_table")) &&
					"identifier".equalsIgnoreCase(((Element)child).getAttribute("openmrs_attribute")))
				return child.getTextContent();
		}

		return null;
	}
}