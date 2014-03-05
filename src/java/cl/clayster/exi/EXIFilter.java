package cl.clayster.exi;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import javax.xml.transform.TransformerException;

import org.apache.commons.io.FileUtils;
import org.apache.mina.common.ByteBuffer;
import org.apache.mina.common.IoFilterAdapter;
import org.apache.mina.common.IoSession;
import org.apache.xerces.impl.dv.util.Base64;
import org.dom4j.DocumentException;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.jivesoftware.util.JiveGlobals;
import org.xml.sax.SAXException;
import org.xmpp.packet.JID;

import com.siemens.ct.exi.CodingMode;
import com.siemens.ct.exi.exceptions.EXIException;

/**
 * This class is a filter that recognizes EXI sessions and adds an EXIEncoder and an EXIDecoder to those sessions. 
 * It also implements the basic EXI variables shared by the EXIEncoder and EXIDecoder such as the Grammars. 
 *
 * @author Javier Placencio
 */
public class EXIFilter extends IoFilterAdapter {
	
	public static final String EXI_PROCESSOR = EXIProcessor.class.getName();
	public static final String filterName = "exiFilter";
	private boolean enabled = true;
	
	public static HashMap<JID, IoSession> sessions = new HashMap<JID, IoSession>(); //TODO: remove sessions field �not needed?

    public EXIFilter() {
        enabled = JiveGlobals.getBooleanProperty("plugin.exi", true);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        JiveGlobals.setProperty("plugin.xmldebugger.", Boolean.toString(enabled)); 
    }
    
    @Override
    public void filterWrite(NextFilter nextFilter, IoSession session, WriteRequest writeRequest) throws Exception {
    	if(writeRequest.getMessage() instanceof ByteBuffer){
    		int currentPos = ((ByteBuffer) writeRequest.getMessage()).position();
    		String msg = Charset.forName("UTF-8").decode(((ByteBuffer) writeRequest.getMessage()).buf()).toString();
    		((ByteBuffer) writeRequest.getMessage()).position(currentPos);
    		if(msg.contains("</compression>")){
    			msg = msg.replace("</compression>", "<method>exi</method></compression>");
    			writeRequest = new WriteRequest(ByteBuffer.wrap(msg.getBytes()), writeRequest.getFuture(), writeRequest.getDestination());
    		}
    	}
    	super.filterWrite(nextFilter, session, writeRequest);
    }
    
    /**
     * <p>Identifies EXI sessions (based on distinguishing bits -> should be based on Negotiation) and adds an EXIEncoder and EXIDecoder to that session</p>
     * @throws Exception 
     *	
     */
    @Override
    public void messageReceived(NextFilter nextFilter, IoSession session, Object message) throws Exception {
    	if(message instanceof String){
    		Element msg = null;
    		try{
    			msg = DocumentHelper.parseText((String) message).getRootElement();
    		} catch (DocumentException e){
    			super.messageReceived(nextFilter, session, message);
    			return;
    		}
			if(msg.getName().equals("setup")){
				String setupResponse = setupResponse(msg, session);
	    		if(setupResponse == null){
	    			System.err.println("An error occurred while processing the negotiation.");
	    		}else{
	    			ByteBuffer bb = ByteBuffer.wrap(setupResponse.getBytes());
	    	        session.write(bb);
	    		}
	    		throw new Exception("<setup> PROCESSED!!!!!");
			}
			else if(msg.getName().equals(("downloadSchema"))){
				String url = msg.attributeValue("null", "url");
				if(url != null){
					String respuesta = "";
					try{
						String descarga = EXIUtils.downloadXml(url);
		    			if(descarga.startsWith("<downloadSchemaResponse ")){
		    				// error already found during download process
		    				respuesta = descarga;
		    			}
		    			else{	// SUCCESS!
		    				saveDownloadedSchema(descarga, session);
		    				respuesta = "<downloadSchemaResponse xmlns='http://jabber.org/protocol/compress/exi' url='" + url + "' result='true'/>";
		    			}
					}catch (DocumentException e){	// error while parsing the just saved file, not probable (exception makes sense while uploading)
						respuesta = "<downloadSchemaResponse xmlns='http://jabber.org/protocol/compress/exi' url='" + url
								+ "' result='false'><invalidContentType contentTypeReturned='text/html'/></downloadSchemaResponse>";
					}catch (Exception e){
	    				respuesta = "<downloadSchemaResponse xmlns='http://jabber.org/protocol/compress/exi' url='" + url
	    						+ "' result='false'><error message='No free space left.'/></downloadSchemaResponse>";
	    			}
	    			session.write(ByteBuffer.wrap((respuesta).getBytes()));
	    			throw new Exception("<downloadSchemaResponse> PROCESSED!!!!!");
				}
			}
			else if(msg.getName().equals("compress")
					&& msg.elementText("method").equalsIgnoreCase("exi")){
				if(createExiProcessor(session)){
					String respuesta = "<compressed xmlns='http://jabber.org/protocol/compress'/>";
	    			ByteBuffer bb = ByteBuffer.wrap(respuesta.getBytes());
	    	        session.write(bb);
	    	        addCodec(session);
	    		}
	    		else{
	    			ByteBuffer bb = ByteBuffer.wrap("<failure xmlns=\'http://jabber.org/protocol/compress\'><setup-failed/></failure>".getBytes());
	    	        session.write(bb);
	    		}
				throw new Exception("<compress> PROCESSED!!!!!");
			}
    	}
    	super.messageReceived(nextFilter, session, message);
    }

    
/**
 * Parses a <setup> stanza sent by the client and generates a corresponding <setupResponse> stanza. It also creates a Configuration Id, which is
 * a UUID followed by '-'; 1 or 0 depending if the configuration is <b>strict</b> or not; and the <b>block size</b> for the EXI Options to use. 
 * @param message A <code>String</code> containing the setup stanza
 * @param session the IoSession that represents the connection to the client
 * @return
 */
	private String setupResponse(Element setup, IoSession session){
		String setupResponse = null;
		String configId = "";
		
		//quick setup	 
		configId = setup.attributeValue("configurationId"); 
		if(configId != null){
			String agreement;
			if(new File(JiveGlobals.getHomeDirectory() + EXIUtils.exiSchemasFolder + configId + ".xsd").exists()){
				EXISetupConfiguration exiConfig = EXIUtils.parseQuickConfigId(configId);
				session.setAttribute(EXIUtils.EXI_CONFIG, exiConfig);
				session.setAttribute(EXIUtils.CANONICAL_SCHEMA_LOCATION, JiveGlobals.getHomeDirectory() + EXIUtils.exiSchemasFolder + configId + ".xsd");
				agreement = "true";
			}
			else{
				agreement = "false";
			}
			return "<setupResponse xmlns='http://jabber.org/protocol/compress/exi' agreement='" + agreement + "' configurationId='" + configId + "'/>";
		}
		
		if(!new File(JiveGlobals.getHomeDirectory() + EXIUtils.schemasFileLocation).exists()){
			try {
				generateSchemasFile(JiveGlobals.getHomeDirectory() + EXIUtils.schemasFolder);
			} catch (NoSuchAlgorithmException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		try {
			// obtener el schemas File del servidor y transformarlo a un elemento XML
			Element serverSchemas;
	        String schemasFileContent = EXIUtils.readFile(JiveGlobals.getHomeDirectory() + EXIUtils.schemasFileLocation);
	        if(schemasFileContent == null){
	        	return null;
	        }
	        serverSchemas = DocumentHelper.parseText(schemasFileContent).getRootElement();	        
	        
    		boolean missingSchema;
    		Element auxSchema1, auxSchema2;
    		String ns, bytes, md5Hash;
    		boolean agreement = true;	// turns to false when there is a missing schema 
	        for (@SuppressWarnings("unchecked") Iterator<Element> i = setup.elementIterator("schema"); i.hasNext();) {
	        	auxSchema1 = i.next();
	        	missingSchema = true;
	        	ns = auxSchema1.attributeValue("ns");
	        	bytes = auxSchema1.attributeValue("bytes");
	        	md5Hash = auxSchema1.attributeValue("md5Hash");
	        	for(@SuppressWarnings("unchecked") Iterator<Element> j = serverSchemas.elementIterator("schema"); j.hasNext();){
	        		auxSchema2 = j.next();
	        		if(auxSchema2.attributeValue("ns").equals(ns)
	        				&& auxSchema2.attributeValue("bytes").equals(bytes)
	        				&& auxSchema2.attributeValue("md5Hash").equals(md5Hash)){
	        			missingSchema = false;
		            	break;
		            }
	        	}
	        	if(missingSchema){
	        		auxSchema1.setName("missingSchema");
	        		if(agreement)	agreement = false;
	        	}
	        }
	        
        	EXISetupConfiguration exiConfig = new EXISetupConfiguration();
	        // guardar el valor de blockSize y strict en session
	        String aux = setup.attributeValue(EXIUtils.ALIGNMENT);
	        if(aux != null || "".equals(aux)){
				if(aux.equals("bit-packed"))	exiConfig.setAlignment(CodingMode.BIT_PACKED);
				else if(aux.equals("byte-packed"))	exiConfig.setAlignment(CodingMode.BYTE_PACKED);
				else if(aux.equals("pre-compression"))	exiConfig.setAlignment(CodingMode.PRE_COMPRESSION);
				else if(aux.equals("compression"))	exiConfig.setAlignment(CodingMode.COMPRESSION);
				else	exiConfig.setAlignment(EXIProcessor.defaultCodingMode);
			}
	        aux = setup.attributeValue(EXIUtils.BLOCK_SIZE);
			if(aux != null || "".equals(aux)){
				exiConfig.setBlockSize(Integer.parseInt(aux));
			}
			aux = setup.attributeValue(EXIUtils.STRICT);
			if(aux != null || "".equals(aux)){
				exiConfig.setStrict(Boolean.valueOf(aux));
			}
			aux = setup.attributeValue(EXIUtils.VALUE_MAX_LENGTH);
			if(aux != null || "".equals(aux)){
				exiConfig.setValueMaxLength(Integer.parseInt(aux));
			}
			aux = setup.attributeValue(EXIUtils.VALUE_PARTITION_CAPACITY);
			if(aux != null || "".equals(aux)){
				exiConfig.setValuePartitionCapacity(Integer.parseInt(aux));
			}
	        /**
	         * configId:
	         *  The first 36 (indexes 0-35) are just the UUID, number 37 is '_' (index 36)
				The next digit (index 37) represents the alignment (0=bit-packed, 1=byte-packed, 2=pre-compression, 3=compression)
				The next digit (index 38) represents if it is strict or not
				The next number represents blocksize (until the next '_')
				Next number between dashes is valueMaxLength
				Last number is valuePartitionCapacity
	         */
	        configId = UUID.randomUUID().toString() + '_' + exiConfig.getAlignmentCode() + (exiConfig.isStrict() ? "1":"0") 
	        		+ exiConfig.getBlockSize() + '_' + exiConfig.getValueMaxLength() + '_' + exiConfig.getValuePartitionCapacity();
	        exiConfig.setId(configId);
	        session.setAttribute(EXIUtils.EXI_CONFIG, exiConfig);
	        session.setAttribute(EXIUtils.CONFIG_ID, configId);	// still necessary for uploading schemas with UploadSchemaFilter
	        
	        // generate canonical schema
	        serverSchemas = createCanonicalSchema(serverSchemas, session);
	        if(!agreement){
	        	session.getFilterChain().addBefore("xmpp", "uploadSchemaFilter", new UploadSchemaFilter(this));
	        }
	        setup.addAttribute("agreement", String.valueOf(agreement));
	        setup.setName("setupResponse");
	        setup.addAttribute("configurationId", configId);
	        
	        setupResponse = setup.asXML();
		} catch (FileNotFoundException e1) {
			e1.printStackTrace();
			return null;
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		} catch (DocumentException e) {
			e.printStackTrace();
			return null;
		}
		return setupResponse;
    }
	
	
	
	/**
	 * Looks for all schema files (*.xsd) in the given folder and creates two new files: 
	 * a canonical schema file which imports all existing schema files;
	 * and an xml file called schema.xml which contains each schema namespace, file size in bytes and its md5Hash code 
	 *   
	 * 
	 * @param folderLocation
	 * @throws NoSuchAlgorithmException
	 * @throws IOException
	 */
	private void generateSchemasFile(String folderLocation) throws NoSuchAlgorithmException, IOException {
		File folder = new File(folderLocation);
		if(!folder.exists()){
			folder.mkdir();
		}
		if(!new File(JiveGlobals.getHomeDirectory() + EXIUtils.exiSchemasFolder).exists()){
			new File(JiveGlobals.getHomeDirectory() + EXIUtils.exiSchemasFolder).mkdir();
		}
        File[] listOfFiles = folder.listFiles();
        File file;
        String fileLocation;
        
		MessageDigest md = MessageDigest.getInstance("MD5");
		InputStream is;
		DigestInputStream dis;
		
		String namespace = null, md5Hash = null;
		int r;
		
		// variables to write the stanzas in the right order (namepsace alfabethical order)
        List<String> namespaces = new ArrayList<String>();		
        HashMap<String, String> schemasStanzas = new HashMap<String, String>();	
        int n = 0;
            
            for (int i = 0; i < listOfFiles.length; i++) {
            	file = listOfFiles[i];
            	if (file.isFile() && file.getName().endsWith(".xsd") && !file.getName().endsWith("canonicalSchema.xsd")) {
            	// se hace lo siguiente para cada archivo XSD en la carpeta folder	
            		fileLocation = file.getAbsolutePath();
					r = 0;
					md.reset();
					StringBuilder sb = new StringBuilder();
	            	
					if(fileLocation == null)	break;
					is = new FileInputStream(fileLocation);
					dis = new DigestInputStream(is, md);
					
					// leer el archivo y guardarlo en sb
					while(r != -1){
						r = dis.read();
						sb.append((char)r);
					}
					
					// buscar el namespace del schema
					namespace = EXIUtils.getAttributeValue(sb.toString(), "targetNamespace");
					md5Hash = EXIUtils.bytesToHex(md.digest());
	
					n = 0;
					while(n < namespaces.size() &&
							namespaces.get(n) != null &&
							namespaces.get(n)
							.compareToIgnoreCase(namespace) <= 0){
						n++;
					}
					namespaces.add(n, namespace);
					// schemasStanzas also contains schemaLocation to make it easier to generate a new canonicalSchema later
					schemasStanzas.put(namespace, "<schema ns='" + namespace + "' bytes='" + file.length() + "' md5Hash='" + md5Hash + "' schemaLocation='" + fileLocation + "'/>");
            	}
			}
            //variables to write the schemas files
            BufferedWriter stanzasWriter = null;
            File stanzasFile = new File(JiveGlobals.getHomeDirectory() + EXIUtils.schemasFileLocation);
            stanzasWriter = new BufferedWriter(new FileWriter(stanzasFile));
            
            stanzasWriter.write("<setupResponse>");
            for(String ns : namespaces){
            	stanzasWriter.write("\n\t" + schemasStanzas.get(ns));
            }
            stanzasWriter.write("\n</setupResponse>");
			stanzasWriter.close();
	}
	
	/**
	 * Generates a canonical schema out of the schemas' namespaces sent in the <setup> stanza during EXI compression negotiation. 
	 * Once the server makes sure that it has all schemas needed, it creates a specific canonical schema for the connection being negotiated.
	 * It takes the location from a general canonical schema which includes all the schemas contained in a given folder.
	 * 
	 * @param schemasStanzas
	 * @throws IOException
	 */
	private Element createCanonicalSchema(Element setup, IoSession session) throws IOException {
		File newCanonicalSchema = new File(JiveGlobals.getHomeDirectory() + EXIUtils.exiSchemasFolder + ((EXISetupConfiguration)session.getAttribute(EXIUtils.EXI_CONFIG)).getId() + ".xsd");
        BufferedWriter newCanonicalSchemaWriter = new BufferedWriter(new FileWriter(newCanonicalSchema));
        newCanonicalSchemaWriter.write("<?xml version='1.0' encoding='UTF-8'?> \n\n<xs:schema \n\txmlns:xs='http://www.w3.org/2001/XMLSchema' \n\ttargetNamespace='urn:xmpp:exi:cs' \n\txmlns='urn:xmpp:exi:cs' \n\telementFormDefault='qualified'>\n");
        
		Element schema;
        for (@SuppressWarnings("unchecked") Iterator<Element> i = setup.elementIterator("schema"); i.hasNext(); ) {
        	schema = i.next();
        	newCanonicalSchemaWriter.write("\n\t<xs:import namespace='" + schema.attributeValue("ns") + "' schemaLocation='" + schema.attributeValue("schemaLocation") + "'/>");
        	schema.remove(schema.attribute("schemaLocation"));
        }
        newCanonicalSchemaWriter.write("\n</xs:schema>");
        newCanonicalSchemaWriter.close();
        
        session.setAttribute(EXIUtils.CANONICAL_SCHEMA_LOCATION, newCanonicalSchema.getCanonicalPath());
        return setup;
	}
	
	
/** Compress **/
	/**
     * Associates an EXIDecoder and an EXIEncoder to this user's session.
     * 
     * @param session IoSession associated to the user's socket
     * @return 
     */
    private boolean createExiProcessor(IoSession session){
        EXIProcessor exiProcessor;
		try {
			EXISetupConfiguration exiConfig = (EXISetupConfiguration) session.getAttribute(EXIUtils.EXI_CONFIG);
			exiProcessor = new EXIProcessor((String)session.getAttribute(EXIUtils.CANONICAL_SCHEMA_LOCATION), exiConfig);
		} catch (EXIException e) {
			e.printStackTrace();
			return false;
		}
		session.setAttribute(EXI_PROCESSOR, exiProcessor);
		return true;
    }
    
    /**
     * Adds an EXIEncoder as well as an EXIDecoder to the given IoSession
     * @param session the IoSession where the EXI encoder and decoder will be added to.
     */
    private void addCodec(IoSession session){
		session.getFilterChain().addBefore("xmpp", "exiDecoder", new EXICodecFilter());
    	session.getFilterChain().remove(EXIFilter.filterName);	
        return;
    }
    
    
/** uploadSchema **/
    
    /**
     * Saves a new schema file on the server, which is sent using a Base64 encoding by an EXI client. 
     * The name of the file is related to the time when the file was saved.
     * 
     * @param content the content of the uploaded schema file (base64 encoded)
     * @return The absolute pathname string denoting the newly created schema file.
     * @throws IOException while trying to decode the file content using Base64
     * @throws DocumentException 
     * @throws NoSuchAlgorithmException 
     * @throws TransformerException 
     * @throws SAXException 
     * @throws EXIException 
     */
    void uploadMissingSchema(String content, IoSession session) 
    		throws IOException, NoSuchAlgorithmException, DocumentException, EXIException, SAXException, TransformerException{
    	String filePath = JiveGlobals.getHomeDirectory() + EXIUtils.schemasFolder + Calendar.getInstance().getTimeInMillis() + ".xsd";
    	OutputStream out = new FileOutputStream(filePath);
    	
    	content = content.substring(content.indexOf('>') + 1, content.indexOf("</"));
		byte[] outputBytes = content.getBytes();
		
    	outputBytes = Base64.decode(content);
    	out.write(outputBytes);
    	out.close();
    	
		String ns = addNewSchemaToSchemasFile(filePath, null, null);
		addNewSchemaToCanonicalSchema(filePath, ns, session);
	}
    
    void uploadCompressedMissingSchema(byte[] content, String contentType, String md5Hash, String bytes, IoSession session) 
    		throws IOException, NoSuchAlgorithmException, DocumentException, EXIException, SAXException, TransformerException{
    	String filePath = JiveGlobals.getHomeDirectory() + EXIUtils.schemasFolder + Calendar.getInstance().getTimeInMillis() + ".xsd";
		
    	if(!"text".equals(contentType) && md5Hash != null && bytes != null){
    		String xml = "";
			if(contentType.equals("ExiDocument")){
    			xml = EXIProcessor.decodeSchemaless(content);
    			
    		}
    		else if(contentType.equals("ExiBody")){
    			xml = EXIProcessor.decodeExiBodySchemaless(content);
    		}	
			EXIUtils.writeFile(filePath, xml);
    	}
    	
		String ns = addNewSchemaToSchemasFile(filePath, md5Hash, bytes);
		addNewSchemaToCanonicalSchema(filePath, ns, session);
	}
    
    /**
     * Saves an uploaded schema to a file. It also processes its md5Hash value and the length in bytes when those parameters are null (for base64 encoded files).
     * 
     * @param fileLocation
     * @param md5Hash md5Hash for the file content for compressed files or null for base64 files
     * @param bytes number of the file's bytes for compressed files or null for base64 files
     * @return the namespace of the schema being saved
     * @throws NoSuchAlgorithmException
     * @throws IOException
     * @throws DocumentException
     */
    String addNewSchemaToSchemasFile(String fileLocation, String md5Hash, String bytes) throws NoSuchAlgorithmException, IOException, DocumentException {
    	MessageDigest md = MessageDigest.getInstance("MD5");
    	File file = new File(fileLocation);
    	if(md5Hash == null || bytes == null){
    		md5Hash = EXIUtils.bytesToHex(md.digest(FileUtils.readFileToByteArray(file)));
    	}
		String ns = EXIUtils.getAttributeValue(EXIUtils.readFile(fileLocation), "targetNamespace");
		
		// obtener el schemas File del servidor y transformarlo a un elemento XML
		Element serverSchemas;
		
		if(!new File(JiveGlobals.getHomeDirectory() + EXIUtils.schemasFileLocation).exists()){	// no hay ning�n schema	(s�lo el nuevo)
        	EXIUtils.writeFile(JiveGlobals.getHomeDirectory() + EXIUtils.schemasFileLocation, "<setupResponse>\n"
        			+ "<schema ns='" + ns + "' bytes='" + ((bytes == null) ? file.length() : bytes) + "' md5Hash='" + md5Hash + "' schemaLocation='" + fileLocation + "'/>"
        			+ "</setupResponse>");
        }
		
        BufferedReader br = new BufferedReader(new FileReader(JiveGlobals.getHomeDirectory() + EXIUtils.schemasFileLocation));
        StringBuilder sb = new StringBuilder();
        String line = br.readLine();

        while (line != null) {
            sb.append(line);
            line = br.readLine();
        }
        br.close();
        
        serverSchemas = DocumentHelper.parseText(sb.toString()).getRootElement();
        
        Element auxSchema;
        @SuppressWarnings("unchecked")
		Iterator<Element> j = serverSchemas.elementIterator("schema");
        int i = 0;	// �ndice donde debe ir el nuevo schema (en la lista de schemas)
    	while(j.hasNext()){
    		auxSchema = j.next();
			if(ns.compareToIgnoreCase(auxSchema.attributeValue("ns")) < 0){
				// se debe quedar en esta posici�n
				break;
			}
			i++;	// debe aumentar su posici�n solo si es mayor al �ltimo namespace comparado
        }
        
        int i2 = 0; // �ndice donde debe ir el nuevo schema (en el archivo)
    	for (int k = -1 ; k < i ; k++){
        	i2 = sb.indexOf("<schema ", i2 + 1);
        }
    	
    	String schema = "<schema ns='" + ns + "' bytes='" + ((bytes == null) ? file.length() : bytes) + "' md5Hash='" + md5Hash + "' schemaLocation='" + fileLocation + "'/>";
    	if(i2 == -1){	// debe ir despues de todos (no sigui� encontrando schemas)
    		sb.insert(sb.indexOf("</setup"), schema);
    	}
    	else{	// debe ir antes de uno que se encontro en i2
    		sb.insert(i2, schema);
    	}
    	
        
    	BufferedWriter schemaWriter = new BufferedWriter(new FileWriter(JiveGlobals.getHomeDirectory() + EXIUtils.schemasFileLocation));
		schemaWriter.write(sb.toString());
		schemaWriter.close();
		
		return ns;
	}

	void addNewSchemaToCanonicalSchema(String fileLocation, String ns, IoSession session) throws IOException{
		// obtener el schemas File del servidor y transformarlo a un elemento XML
		String canonicalSchemaStr = EXIUtils.readFile(JiveGlobals.getHomeDirectory() + EXIUtils.exiSchemasFolder + session.getAttribute(EXIUtils.CONFIG_ID) + ".xsd");
		StringBuilder canonicalSchemaStrBuilder = new StringBuilder();
		if(canonicalSchemaStr != null && canonicalSchemaStr.indexOf("namespace") != -1){
	        	canonicalSchemaStrBuilder = new StringBuilder(canonicalSchemaStr);
	        	String aux = canonicalSchemaStrBuilder.toString(), importedNamespace = ">";	// importedNamespace hace que se comience justo antes de los xs:import
	        	int index;
	        	do{
	        		aux = aux.substring(aux.indexOf(importedNamespace) + importedNamespace.length());
	        		importedNamespace = EXIUtils.getAttributeValue(aux, "namespace");
	        	}while(importedNamespace != null && ns.compareTo(importedNamespace) > 0 && aux.indexOf("<xs:import ") != -1);
	        	index = canonicalSchemaStrBuilder.indexOf(aux.substring(aux.indexOf('>') + 1));
	        	canonicalSchemaStrBuilder.insert(index, "\n\t<xs:import namespace='" + ns + "' schemaLocation='" + fileLocation + "'/>");
	        }
        else{
        	canonicalSchemaStrBuilder = new StringBuilder();
        	canonicalSchemaStrBuilder.append("<?xml version='1.0' encoding='UTF-8'?> \n\n<xs:schema \n\txmlns:xs='http://www.w3.org/2001/XMLSchema' \n\ttargetNamespace='urn:xmpp:exi:cs' \n\txmlns='urn:xmpp:exi:cs' \n\telementFormDefault='qualified'>\n");
        	canonicalSchemaStrBuilder.append("\n\t<xs:import namespace='" + ns + "' schemaLocation='" + fileLocation + "'/>");
        	canonicalSchemaStrBuilder.append("\n</xs:schema>");
		}
        
        File canonicalSchema = new File(JiveGlobals.getHomeDirectory() + EXIUtils.exiSchemasFolder + session.getAttribute(EXIUtils.CONFIG_ID) + ".xsd");
        BufferedWriter canonicalSchemaWriter = new BufferedWriter(new FileWriter(canonicalSchema));
        canonicalSchemaWriter.write(canonicalSchemaStrBuilder.toString());
        canonicalSchemaWriter.close();
        
        session.setAttribute(EXIUtils.CANONICAL_SCHEMA_LOCATION, canonicalSchema.getAbsolutePath());
	}
    
/* downloadSchema */ 
 
    
    /**
     * 
     * @param schema
     * @param session
     * @throws NoSuchAlgorithmException
     * @throws IOException
     * @throws DocumentException 
     **/
    private void saveDownloadedSchema(String content, IoSession session) throws NoSuchAlgorithmException, IOException, DocumentException {
    	String filePath = EXIUtils.schemasFolder + Calendar.getInstance().getTimeInMillis() + ".xsd";
    	
    	OutputStream out = new FileOutputStream(filePath);
    	out.write(content.getBytes());
    	out.close();
    	
        String ns = addNewSchemaToSchemasFile(filePath, null, null);
		addNewSchemaToCanonicalSchema(filePath, ns, session);
	}
}
