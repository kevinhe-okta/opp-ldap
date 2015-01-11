package com.okta.scim.server.example;

import com.okta.scim.server.capabilities.UserManagementCapabilities;
import com.okta.scim.server.exception.DuplicateGroupException;
import com.okta.scim.server.exception.EntityNotFoundException;
import com.okta.scim.server.exception.OnPremUserManagementException;
import com.okta.scim.server.service.SCIMOktaConstants;
import com.okta.scim.server.service.SCIMService;
import com.okta.scim.util.model.Email;
import com.okta.scim.util.model.Membership;
import com.okta.scim.util.model.Name;
import com.okta.scim.util.model.PaginationProperties;
import com.okta.scim.util.model.SCIMFilter;
import com.okta.scim.util.model.SCIMFilterType;
import com.okta.scim.util.model.SCIMGroup;
import com.okta.scim.util.model.SCIMGroupQueryResponse;
import com.okta.scim.util.model.SCIMUser;
import com.okta.scim.util.model.SCIMUserQueryResponse;
import com.okta.scim.util.model.PhoneNumber;

import org.apache.log4j.Logger;
import org.codehaus.jackson.JsonNode;
import org.springframework.util.StringUtils;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.commons.configuration.ConfigurationException;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.InputStream;
import java.util.Properties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.security.MessageDigest;
import javax.naming.directory.SearchControls;
import javax.naming.directory.Attributes;
import javax.naming.directory.Attribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.SearchResult;
import javax.naming.directory.InvalidAttributeValueException;
import javax.naming.NamingEnumeration;
import javax.naming.Context;
import javax.naming.ldap.LdapContext;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.NamingException;
import javax.annotation.PostConstruct;
import javax.xml.parsers.SAXParser;

public class SCIMServiceImpl implements SCIMService {
	//Absolute path for users.json set in the dispatcher-servlet.xml
	private String usersFilePath;
	//Absolute path for groups.json set in the dispatcher-servlet.xml
	private String groupsFilePath;
	//Ldap settings
	private String ldapBaseDn;
	private String ldapGroupDn;
	private String ldapUserDn;
	private String ldapUserPre;
	private String ldapGroupPre;
	private String ldapUserFilter;
	private String ldapGroupFilter;
	private String ldapInitialContextFactory;
	private String ldapUrl;
	private String ldapSecurityAuthentication;
	private String ldapSecurityPrincipal;
	private String ldapSecurityCredentials;
	private String[] ldapUserClass;
	private String[] ldapGroupClass;
	private String USER_RESOURCE = "user";
	private String GROUP_RESOURCE = "group";
	//Field names for the custom properties
	private static final String CUSTOM_SCHEMA_PROPERTY_IS_ADMIN = "isAdmin";
	private static final String CUSTOM_SCHEMA_PROPERTY_IS_OKTA = "isOkta";
	private static final String CUSTOM_SCHEMA_PROPERTY_DEPARTMENT_NAME = "departmentName";
	//This should be the name of the App you created. On the Okta URL for the App, you can find this name
	private static final String APP_NAME = "onprem_app";
	//This should be the name of the Universal Directory schema you created. We are assuming this name is "custom"
	private static final String UD_SCHEMA_NAME = "custom";
	private static final Logger LOGGER = Logger.getLogger(SCIMServiceImpl.class);
	private static final String CONF_FILENAME = "connector.properties";

	private Map<String, SCIMUser> userMap = new HashMap<String, SCIMUser>();
	private Map<String, SCIMGroup> groupMap = new HashMap<String, SCIMGroup>();
	private int nextUserId;
	private int nextGroupId;
	private String userCustomUrn;
	private boolean useFilePersistence = true;
	private Hashtable env = new Hashtable(11);

	@PostConstruct
	public void afterCreation() throws Exception {
		userCustomUrn = SCIMOktaConstants.CUSTOM_URN_PREFIX + APP_NAME + SCIMOktaConstants.CUSTOM_URN_SUFFIX + UD_SCHEMA_NAME;
		initLdapVars();
		env.put(Context.INITIAL_CONTEXT_FACTORY, ldapInitialContextFactory);
		env.put(Context.PROVIDER_URL, ldapUrl);
		env.put(Context.SECURITY_AUTHENTICATION, ldapSecurityAuthentication);
		env.put(Context.SECURITY_PRINCIPAL, ldapSecurityPrincipal);
		env.put(Context.SECURITY_CREDENTIALS, ldapSecurityCredentials);
		nextUserId = 100;
		nextGroupId = 1000;
		initUsers();
		initGroups();
	}

	private void initLdapVars() {
		Configuration config;
		try {
			config = new PropertiesConfiguration(CONF_FILENAME);
			ldapBaseDn = config.getString("Ldap.base_dn");
			ldapGroupDn = config.getString("Ldap.group_dn");
			ldapUserDn = config.getString("Ldap.user_dn");
			ldapGroupPre = config.getString("Ldap.group_pre");
			ldapUserPre = config.getString("Ldap.user_pre");
			ldapUserFilter = config.getString("Ldap.user_filter");
			ldapGroupFilter = config.getString("Ldap.group_filter");
			ldapInitialContextFactory = config.getString("Ldap.initial_context_factory");
			ldapUrl = config.getString("Ldap.url");
			ldapSecurityAuthentication = config.getString("Ldap.security_authentication");
			ldapSecurityPrincipal = config.getString("Ldap.security_principal");
			ldapSecurityCredentials = config.getString("Ldap.security_credentials");
			ldapUserClass = config.getStringArray("Ldap.user_class");
			ldapGroupClass = config.getStringArray("Ldap.group_class");
			LOGGER.debug(Arrays.toString(ldapUserClass));
		} catch (ConfigurationException e) {
			StringWriter errors = new StringWriter();
			e.printStackTrace(new PrintWriter(errors));
			LOGGER.error(errors.toString());
		}
	}

	private void initUsers() throws Exception {
		try {
			LdapContext ctx = new InitialLdapContext(env, null);
			String dn = ldapUserDn + ldapBaseDn;
			ctx.setRequestControls(null);
			SearchControls controls = new SearchControls();
			controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
			NamingEnumeration<?> namingEnum = ctx.search(dn, ldapUserFilter, controls);
			int counter = 0;
			while (namingEnum.hasMore()) {
				SearchResult result = (SearchResult) namingEnum.next();
				Attributes attrs = result.getAttributes();
				SCIMUser user = constructUserFromAttrs(attrs);
				userMap.put(user.getId(), user);
			}
			ctx.close();
			namingEnum.close();
		} catch (Exception e) {
			StringWriter errors = new StringWriter();
			e.printStackTrace(new PrintWriter(errors));
			LOGGER.error(errors.toString());
		}
	}

	private void initGroups() throws Exception {
		try {
			LdapContext ctx = new InitialLdapContext(env, null);
			String dn = ldapGroupDn + ldapBaseDn;
			ctx.setRequestControls(null);
			SearchControls controls = new SearchControls();
			controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
			NamingEnumeration<?> namingEnum = ctx.search(dn, ldapGroupFilter, controls);
			int counter = 0;
			while (namingEnum.hasMore()) {
				SearchResult result = (SearchResult) namingEnum.next();
				Attributes attrs = result.getAttributes();
				SCIMGroup group = constructGroupFromAttrs(attrs);
				groupMap.put(group.getId(), group);
			}
			ctx.close();
			namingEnum.close();
		} catch (Exception e) {
			StringWriter errors = new StringWriter();
			e.printStackTrace(new PrintWriter(errors));
			LOGGER.error(errors.toString());
		}
	}

	public String getUsersFilePath() {
		return usersFilePath;
	}

	public void setUsersFilePath(String usersFilePath) {
		this.usersFilePath = usersFilePath;
	}

	public String getGroupsFilePath() {
		return groupsFilePath;
	}

	public void setGroupsFilePath(String groupsFilePath) {
		this.groupsFilePath = groupsFilePath;
	}

	@Override
	public SCIMUser createUser(SCIMUser user) throws OnPremUserManagementException {
		String id = generateNextId(USER_RESOURCE);
		user.setId(id);
		LOGGER.debug("[createUser] Creating User: " + user.getName().getFormattedName());
	/**
	* Below is an example to show how to deal with exceptional conditions while writing the connector.
	* If you cannot complete the UserManagement operation on the on premises
	* application because of any error/exception, you should throw the OnPremUserManagementException as shown below.
	* <b>Note:</b> You can throw this exception from all the CRUD (Create/Retrieve/Update/Delete) operations defined on
	* Users/Groups in the SCIM interface.
	*/
		if (userMap == null) {
		//Note that the Error Code "o01234" is arbitrary - You can use any code that you want to.
		//You can specify a url which has the documentation/information to help figure out the issue.
		//You can also specify an underlying exception (null in the example below)
		throw new OnPremUserManagementException("o01234", "Cannot create the user. The userMap is null", "http://some-help-url", null);
		}

		try {
			LdapContext ctx = new InitialLdapContext(env, null);
			Attributes attrs = constructAttrsFromUser(user);
			Name fullName = user.getName();
			String dn = ldapUserPre + user.getUserName() + "," + ldapUserDn + ldapBaseDn;
			ctx.createSubcontext(dn, attrs);
			ctx.close();
			LOGGER.debug("[createUser] User " + user.getName().getFormattedName() + " successfully inserted into Directory Service.");
		} catch (Exception e) {
			StringWriter errors = new StringWriter();
			e.printStackTrace(new PrintWriter(errors));
			LOGGER.error(errors.toString());
		}
		userMap.put(user.getId(), user);
		return user;
	}

	public SCIMUser updateUser(String id, SCIMUser user) throws OnPremUserManagementException, EntityNotFoundException {
		/**
		* Below is an example to show how to deal with exceptional conditions while writing the connector.
		* If you cannot complete the UserManagement operation on the on premises
		* application because of any error/exception, you should throw the OnPremUserManagementException as shown below
		* <b>Note:</b> You can throw this exception from all the CRUD (Create/Retrieve/Update/Delete) operations defined on
		* Users/Groups in the SCIM interface.
		*/
		if (userMap == null) {
		//Note that the Error Code "o12345" is arbitrary - You can use any code that you want to.
			throw new OnPremUserManagementException("o12345", "Cannot update the user. The userMap is null");
		}
		LOGGER.debug("[updateUser] Updating user: " + user.getName().getFormattedName());
		SCIMUser existingUser = userMap.get(id);
		if (existingUser != null) {
			userMap.put(id, user);
			Name fullName = existingUser.getName();
			try {
				LdapContext ctx = new InitialLdapContext(env, null);
				String dn = ldapUserPre + user.getUserName() + "," + ldapUserDn + ldapBaseDn;
				ctx.destroySubcontext(dn);
				if(user.isActive()) {
					LOGGER.info("[updateUser] User is still active, re-adding user.");
					Attributes attrs = constructAttrsFromUser(user);
					ctx.createSubcontext(dn, attrs);
				}
				ctx.close();
			} catch (Exception e) {
				StringWriter errors = new StringWriter();
				e.printStackTrace(new PrintWriter(errors));
				LOGGER.error(errors.toString());
			}
			return user;
		} else {
			throw new EntityNotFoundException();
		}
	}

	public SCIMUserQueryResponse getUsers(PaginationProperties pageProperties, SCIMFilter filter) throws OnPremUserManagementException {
		List<SCIMUser> users = new ArrayList<SCIMUser>();
		LOGGER.debug("[getUsers]");
		if (filter != null) {
			//Get users based on a filter
			users = getUserByFilter(filter);
			//Example to show how to construct a SCIMUserQueryResponse and how to set stuff.
			SCIMUserQueryResponse response = new SCIMUserQueryResponse();
			//The total results in this case is set to the number of users. But it may be possible that
			//there are more results than what is being returned => totalResults > users.size();
			response.setTotalResults(users.size());
			//Actual results which need to be returned
			response.setScimUsers(users);
			//The input has some page properties => Set the start index.
			if (pageProperties != null) {
				response.setStartIndex(pageProperties.getStartIndex());
			}
			return response;
		} else {
			return getUsers(pageProperties);
		}
	}

	private SCIMUserQueryResponse getUsers(PaginationProperties pageProperties) {
		SCIMUserQueryResponse response = new SCIMUserQueryResponse();
		/**
		* Below is an example to show how to deal with exceptional conditions while writing the connector.
		* If you cannot complete the UserManagement operation on the on premises
		* application because of any error/exception, you should throw the OnPremUserManagementException as shown below.
		* <b>Note:</b> You can throw this exception from all the CRUD (Create/Retrieve/Update/Delete) operations defined on
		* Users/Groups in the SCIM interface.
		*/
		if (userMap == null) {
			//Note that the Error Code "o34567" is arbitrary - You can use any code that you want to.
			throw new OnPremUserManagementException("o34567", "Cannot get the users. The userMap is null");
		}

		int totalResults = userMap.size();
		if (pageProperties != null) {
			//Set the start index to the response.
			response.setStartIndex(pageProperties.getStartIndex());
		}
		//In this example we are setting the total results to the number of results in this page. If there are more
		//results than the number the client asked for (pageProperties.getCount()), then you need to set the total results correctly
		response.setTotalResults(totalResults);
		List<SCIMUser> users = new ArrayList<SCIMUser>();
		for (String key : userMap.keySet()) {
			users.add(userMap.get(key));
		}
		//Set the actual results
		response.setScimUsers(users);
		return response;
	}

	private List<SCIMUser> getUserByFilter(SCIMFilter filter) {
		List<SCIMUser> users = new ArrayList<SCIMUser>();

		SCIMFilterType filterType = filter.getFilterType();

		if (filterType.equals(SCIMFilterType.EQUALS)) {
			//Example to show how to deal with an Equality filter
			users = getUsersByEqualityFilter(filter);
		} else if (filterType.equals(SCIMFilterType.OR)) {
			//Example to show how to deal with an OR filter containing multiple sub-filters.
			users = getUsersByOrFilter(filter);
		} else {
			LOGGER.error("The Filter " + filter + " contains a condition that is not supported");
		}
		return users;
	}

	private List<SCIMUser> getUsersByOrFilter(SCIMFilter filter) {
		//An OR filter would contain a list of filter expression. Each expression is a SCIMFilter by itself.
		//Ex : "email eq "abc@def.com" OR email eq "def@abc.com""
		List<SCIMFilter> subFilters = filter.getFilterExpressions();
		LOGGER.info("OR Filter : " + subFilters);
		List<SCIMUser> users = new ArrayList<SCIMUser>();
		//Loop through the sub filters to evaluate each of them.
		//Ex : "email eq "abc@def.com""
		for (SCIMFilter subFilter : subFilters) {
			//Name of the sub filter (email)
			String fieldName = subFilter.getFilterAttribute().getAttributeName();
			//Value (abc@def.com)
			String value = subFilter.getFilterValue();
			//For all the users, check if any of them have this email
			for (Map.Entry<String, SCIMUser> entry : userMap.entrySet()) {
				boolean userFound = false;
				SCIMUser user = entry.getValue();
				//In this example, since we assume that the field name configured with Okta is "email", checking if we got the field name as "email" here
				if (fieldName.equalsIgnoreCase("email")) {
					//Get the user's emails and check if the value is the same as in the filter
					Collection<Email> emails = user.getEmails();
					if (emails != null) {
						for (Email email : emails) {
							if (email.getValue().equalsIgnoreCase(value)) {
								userFound = true;
								break;
							}
						}
					}
				}
				if (userFound) {
					users.add(user);
				}
			}
		}
		return users;
	}

	private List<SCIMUser> getUsersByEqualityFilter(SCIMFilter filter) {
		String fieldName = filter.getFilterAttribute().getAttributeName();
		String value = filter.getFilterValue();
		LOGGER.info("Equality Filter : Field Name [ " + fieldName + " ]. Value [ " + value + " ]");
		List<SCIMUser> users = new ArrayList<SCIMUser>();

		//A basic example of how to return users that match the criteria
		for (Map.Entry<String, SCIMUser> entry : userMap.entrySet()) {
			SCIMUser user = entry.getValue();
			boolean userFound = false;
			//Ex : "userName eq "someUserName""
			if (fieldName.equalsIgnoreCase("userName")) {
				String userName = user.getUserName();
				if (userName != null && userName.equals(value)) {
					userFound = true;
				}
			} else if (fieldName.equalsIgnoreCase("id")) {
				//"id eq "someId""
				String id = user.getId();
				if (id != null && id.equals(value)) {
					userFound = true;
				}
			} else if (fieldName.equalsIgnoreCase("name")) {
				String subFieldName = filter.getFilterAttribute().getSubAttributeName();
				Name name = user.getName();
				if (name == null || subFieldName == null) {
					continue;
				}
				if (subFieldName.equalsIgnoreCase("familyName")) {
					//"name.familyName eq "someFamilyName""
					String familyName = name.getLastName();
					if (familyName != null && familyName.equals(value)) {
						userFound = true;
					}
				} else if (subFieldName.equalsIgnoreCase("givenName")) {
					//"name.givenName eq "someGivenName""
					String givenName = name.getFirstName();
					if (givenName != null && givenName.equals(value)) {
						userFound = true;
					}
				}
			} else if (filter.getFilterAttribute().getSchema().equalsIgnoreCase(userCustomUrn)) { //Check that the Schema name is the Custom Schema name to process the filter for custom fields
				/**
				 * The example below shows one of the two ways to get a custom property.<p>
				 * The other way is to use the getter directly to get the value - user.getCustomStringProperty("urn:okta:onprem_app:1.0:user:custom", fieldName, null) will get the value
				 * if the fieldName is a root element. If fieldName is a child of any other field, user.getCustomStringProperty("urn:okta:onprem_app:1.0:user:custom", fieldName, parentName)
				 * will get the value.
				 */
				//"urn:okta:onprem_app:1.0:user:custom:departmentName eq "someValue""
				Map<String, JsonNode> customPropertiesMap = user.getCustomPropertiesMap();
				//Get the custom properties map (SchemaName -> JsonNode)
				if (customPropertiesMap == null || !customPropertiesMap.containsKey(userCustomUrn)) {
					continue;
				}
				//Get the JsonNode having all the custom properties for this schema
				JsonNode customNode = customPropertiesMap.get(userCustomUrn);
				//Check if the node has that custom field
				if (customNode.has(fieldName) && customNode.get(fieldName).asText().equalsIgnoreCase(value)) {
					userFound = true;
				}
			}

			if (userFound) {
				users.add(user);
			}
		}
		return users;
	}

	@Override
	public SCIMUser getUser(String id) throws OnPremUserManagementException, EntityNotFoundException {
		SCIMUser user = userMap.get(id);
		if (user != null) {
			return user;
		} else {
			//If you do not find a user/group by the ID, you can throw this exception.
			throw new EntityNotFoundException();
		}
	}

	public SCIMGroup createGroup(SCIMGroup group) throws OnPremUserManagementException, DuplicateGroupException {
		String displayName = group.getDisplayName();
		LOGGER.debug("[createGroup] Creating group: " + group.getDisplayName());
		boolean duplicate = false;
		/**
		 * Below is an example to show how to deal with exceptional conditions while writing the connector.
		 * If you cannot complete the UserManagement operation on the on premises
		 * application because of any error/exception, you should throw the OnPremUserManagementException as shown below
		 * <b>Note:</b> You can throw this exception from all the CRUD (Create/Retrieve/Update/Delete) operations defined on
		 * Users/Groups in the SCIM interface.
		 */
		if (groupMap == null) {
			//Note that the Error Code "o23456" is arbitrary - You can use any code that you want to.
			throw new OnPremUserManagementException("o23456", "Cannot create the group. The groupMap is null");
		}

		for (Map.Entry<String, SCIMGroup> entry : groupMap.entrySet()) {
			//In this example, let us assume that a group is duplicate if the displayName is the same
			if (entry.getValue().getDisplayName().equalsIgnoreCase(displayName)) {
				duplicate = true;
			}
		}

		if (duplicate) {
			throw new DuplicateGroupException();
		}

		String id = generateNextId(GROUP_RESOURCE);
		group.setId(id);

		try {
			LdapContext ctx = new InitialLdapContext(env, null);
			Attributes attrs = constructAttrsFromGroup(group);
			ctx.createSubcontext(ldapGroupPre + group.getDisplayName() + "," + ldapGroupDn + ldapBaseDn, attrs);
			ctx.close();
			LOGGER.debug("[createGroup] Group " + group.getDisplayName() + " successfully created.");
		} catch (Exception e) {
			if(e instanceof InvalidAttributeValueException) {
				LOGGER.error(((InvalidAttributeValueException) e).getExplanation());
			}
			else {
				StringWriter errors = new StringWriter();
				e.printStackTrace(new PrintWriter(errors));
				LOGGER.error(errors.toString());
			}
		}

		groupMap.put(group.getId(), group);
		return group;
	}

	public SCIMGroup updateGroup(String id, SCIMGroup group) throws OnPremUserManagementException {
		SCIMGroup existingGroup = groupMap.get(id);
		//LOGGER.debug("[updateGroup] Trying to update "+ id + " with: "  + group.toString());
		if (existingGroup != null) {
			try {
				LdapContext ctx = new InitialLdapContext(env, null);
				Attributes attrs = constructAttrsFromGroup(group);
				ctx.destroySubcontext(ldapGroupPre + existingGroup.getDisplayName() + "," + ldapGroupDn + ldapBaseDn);
				LOGGER.debug("[updateGroup] Group " + group.getDisplayName() + " successfully removed.");
				ctx.createSubcontext(ldapGroupPre + group.getDisplayName() + "," + ldapGroupDn + ldapBaseDn, attrs);
				ctx.close();
				LOGGER.debug("[updateGroup] Group " + group.getDisplayName() + " successfully re-created.");
			} catch (Exception e) {
				StringWriter errors = new StringWriter();
				e.printStackTrace(new PrintWriter(errors));
				LOGGER.error(errors.toString());
			}
			groupMap.put(id, group);
			return group;
		} else {
			throw new EntityNotFoundException();
		}
	}

	public SCIMGroupQueryResponse getGroups(PaginationProperties pageProperties) throws OnPremUserManagementException {
		SCIMGroupQueryResponse response = new SCIMGroupQueryResponse();
		int totalResults = groupMap.size();
		if (pageProperties != null) {
			//Set the start index
			response.setStartIndex(pageProperties.getStartIndex());
		}
		//In this example we are setting the total results to the number of results in this page. If there are more
		//results than the number the client asked for (pageProperties.getCount()), then you need to set the total results correctly
		response.setTotalResults(totalResults);
		List<SCIMGroup> groups = new ArrayList<SCIMGroup>();
		for (String key : groupMap.keySet()) {
			groups.add(groupMap.get(key));
		}
		//Set the actual results
		response.setScimGroups(groups);
		return response;
	}

	public SCIMGroup getGroup(String id) throws OnPremUserManagementException {
		SCIMGroup group = groupMap.get(id);
		if (group != null) {
			return group;
		} else {
			//If you do not find a user/group by the ID, you can throw this exception.
			throw new EntityNotFoundException();
		}
	}

	public void deleteGroup(String id) throws OnPremUserManagementException, EntityNotFoundException {
		if (groupMap.containsKey(id)) {
			LOGGER.debug("[deleteGroup] Deleting group: " + id);
			SCIMGroup group = groupMap.remove(id);
			try {
				LdapContext ctx = new InitialLdapContext(env, null);
				ctx.destroySubcontext(ldapGroupPre + group.getDisplayName() + "," + ldapGroupDn + ldapBaseDn);
				ctx.close();
			} catch (Exception e) {
				StringWriter errors = new StringWriter();
				e.printStackTrace(new PrintWriter(errors));
				LOGGER.error(errors.toString());
			}
		} else {
			throw new EntityNotFoundException();
		}
	}

	public UserManagementCapabilities[] getImplementedUserManagementCapabilities() {
		return UserManagementCapabilities.values();
	}

	/**
	 * Generate the next if for a resource
	 *
	 * @param resourceType
	 * @return
	 */
	private String generateNextId(String resourceType) {
		if (useFilePersistence) {
			return UUID.randomUUID().toString();
		}

		if (resourceType.equals(USER_RESOURCE)) {
			return Integer.toString(nextUserId++);
		}

		if (resourceType.equals(GROUP_RESOURCE)) {
			return Integer.toString(nextGroupId++);
		}

		return null;
	}

	private Attributes constructAttrsFromUser(SCIMUser user) {
		String active = user.isActive() ? "active" : "inactive";
		Attributes attrs = new BasicAttributes(true);
		Attribute objclass = new BasicAttribute("objectClass");
		objclass.add("OpenLDAPperson");
		//objclass.add("posixAccount");
		objclass.add("shadowAccount");
		Attribute surname = new BasicAttribute("sn", user.getName().getLastName());
		Attribute uid = new BasicAttribute("uid", user.getUserName());
		Attribute passwd = new BasicAttribute("userPassword");
		Attribute displayName = new BasicAttribute("displayName", user.getName().getFormattedName());
		Attribute givenName = new BasicAttribute("givenName", user.getName().getFirstName());
		Attribute description = new BasicAttribute("description", user.getId());
		Attribute phoneNumsAttr = new BasicAttribute("telephoneNumber");
		Attribute emailsAttr = new BasicAttribute("mail");
		try{
			if(user.getPassword() != null) {
				passwd.add(hashPassword(user.getPassword()));
				attrs.put(passwd);
			}
			if(user.getPhoneNumbers() != null) {
				Object[] phoneNums = user.getPhoneNumbers().toArray();
				for(int i = 0; i < phoneNums.length; i++) {
					PhoneNumber num = (PhoneNumber) phoneNums[i];
					phoneNumsAttr.add(num.getValue() + "," + num.isPrimary() + "," + num.getType().getTypeString());
				}
				attrs.put(phoneNumsAttr);
			}
			if(user.getEmails() != null) {
				Object[] emails = user.getEmails().toArray();
				for(int i = 0; i < emails.length; i++) {
					Email email = (Email) emails[i];//Yo,dawg I hurd you like emails...
					emailsAttr.add(email.getValue() + "|" + email.getType() + "|" + email.isPrimary());
				}
				attrs.put(emailsAttr);
			}
		} catch (Exception e) {
			StringWriter errors = new StringWriter();
			e.printStackTrace(new PrintWriter(errors));
			LOGGER.error(errors.toString());
		}

		LOGGER.debug(passwd.toString());
		attrs.put(objclass);
		attrs.put(uid);
		attrs.put(surname);
		attrs.put(givenName);
		attrs.put(description);
		attrs.put(displayName);
		return attrs;
	}

	private SCIMUser constructUserFromAttrs(Attributes attrs) {
		SCIMUser user = new SCIMUser();
		try {
			String formattedName = attrs.get("displayName").get().toString();
			String sn = attrs.get("sn").get().toString();
			String givenName = attrs.get("givenName").get().toString();
			String uid = attrs.get("description").get().toString();
			String passwd = new String((byte[])attrs.get("userPassword").get());
			ArrayList<PhoneNumber> phoneNums = new ArrayList<PhoneNumber>();
			ArrayList<Email> emails = new ArrayList<Email>();
			Name fullName = new Name(formattedName, sn, givenName);
			Attribute phoneNumsAttr = attrs.get("telephoneNumber");
			Attribute emailsAttr = attrs.get("mail");

			user.setName(fullName);
			user.setUserName(attrs.get("uid").get().toString());
			user.setId(uid);
			user.setActive(true);
			user.setPassword(passwd);

			if(phoneNumsAttr != null) {
				for(int i = 0; i < phoneNumsAttr.size(); i++) {
					String phoneNum = phoneNumsAttr.get(i).toString();
					String[] phoneNumParts = splitString(phoneNum, ",");
					if(phoneNumParts.length > 2) {
						PhoneNumber.PhoneNumberType type = PhoneNumber.PhoneNumberType.valueOf(phoneNumParts[2].toUpperCase());
						PhoneNumber numEntry = new PhoneNumber(phoneNumParts[0], type, Boolean.parseBoolean(phoneNumParts[1]));
						phoneNums.add(numEntry);
					}
					else {
						LOGGER.error("[constructUserFromAttrs] String: " + phoneNum + "was ill formatted, expected 3 segments.");
					}
				}
				user.setPhoneNumbers(phoneNums);
			}
			if(emailsAttr != null) {
				for(int i = 0; i < emailsAttr.size(); i++) {
					String email = emailsAttr.get(i).toString();
					String[] emailParts = splitString(email, "|");
					if(emailParts.length > 2) {
						Email emailEntry = new Email(emailParts[0], emailParts[1], Boolean.parseBoolean(emailParts[2]));
						emails.add(emailEntry);
					}
					else {
						LOGGER.error("[constructUserFromAttrs] String: " + email + "was ill formatted, expected 3 segments.");
					}
				}
				user.setEmails(emails);
			}
			return user;
		} catch (Exception e) {
			StringWriter errors = new StringWriter();
			e.printStackTrace(new PrintWriter(errors));
			LOGGER.error(errors.toString());
		}
		return user;
	}

	//NOTE: if you don't select the delete in app option when deleting a pushed group
	//Okta will remember the id of the group.
	private Attributes constructAttrsFromGroup(SCIMGroup group) {
		Attributes attrs = new BasicAttributes(true);
		LOGGER.info("[constructAttrsFromGroup] constructing Attrs from group");
		try {
			Attribute objclass = new BasicAttribute("objectClass");
			objclass.add("posixGroup");
			Attribute description = new BasicAttribute("description", group.getId());
			Attribute gidNum = new BasicAttribute("gidNumber", "5000");
			Attribute memAttr = new BasicAttribute("memberUid");
			//ArrayList<Attribute> membersAttr = new ArrayList<Attribute>();
			attrs.put(objclass);
			attrs.put(description);
			attrs.put(gidNum);

			if(group.getMembers() != null ) {
				Object[] members = group.getMembers().toArray();
				for(int i = 0; i < members.length; i++) {
					Membership mem = (Membership) members[i];
					memAttr.add(mem.getId()+ "|"+ mem.getDisplayName());
				}
				//attrs.put(membersAttr);
				attrs.put(memAttr);
			}
		} catch (Exception e) {
			StringWriter errors = new StringWriter();
			e.printStackTrace(new PrintWriter(errors));
			LOGGER.error(errors.toString());
		}
		return attrs;
	}

	private SCIMGroup constructGroupFromAttrs(Attributes attrs) {
		SCIMGroup group = new SCIMGroup();
		try {
			String cn = attrs.get("cn").get().toString();
			ArrayList<Membership> memberList = new ArrayList<Membership>();
			Attribute memberAttr = attrs.get("memberUid");
			String id = attrs.get("description").get().toString();
			group.setDisplayName(cn);
			group.setId(id);

			if(memberAttr != null) {
				for(int i = 0; i < memberAttr.size(); i++) {
					String memberUid = (String) memberAttr.get(i).toString();
					String[] memberParts = splitString(memberUid, "|");
					if(memberParts.length > 1) {
						Membership memHolder = new Membership(memberParts[0], memberParts[1]);
						memberList.add(memHolder);
					}
					else {
						LOGGER.error("[constructGroupFromAttrs] String: " + memberUid + "was ill formatted, expected 3 segments.");
					}
				}
				group.setMembers(memberList);
			}
		} catch (Exception e) {
			StringWriter errors = new StringWriter();
			e.printStackTrace(new PrintWriter(errors));
			LOGGER.error(errors.toString());
		}
		return group;
	}

	private String hashPassword(String password) throws Exception {//NoSuchAlgorithmException, UnsupportedEncodingException {
		MessageDigest digest = MessageDigest.getInstance("SHA");
		digest.update(password.getBytes("UTF8"));
		byte[] encodedBytes = Base64.encodeBase64(digest.digest());
		String shaPassword = new String(encodedBytes);
		return password;
		//return "{SHA}" + shaPassword;
	}

	private String[] splitString(String s, String delim) throws OnPremUserManagementException{
		if(s.contains(delim)) {
			String[] sParts = s.split(Pattern.quote(delim));//split uses regex, contains uses string literals
			return sParts;
		} else {
			LOGGER.error("[splitString] " + "Cannot parse: " + s + "using delimiter: " + delim);
			throw new OnPremUserManagementException("o2313", "Cannot parse: " + s + "using delimiter: " + delim);
		}
	}
}

