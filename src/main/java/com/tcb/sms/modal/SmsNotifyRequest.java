package com.tcb.sms.modal;

public class SmsNotifyRequest {

	  private String name;
	  private String location;
	  private String lang;
	  private String email;
	  private String phone;

	  public SmsNotifyRequest() {
	  }

	  public String getName() {
	    return name;
	  }
	  public void setName(String name) {
	    this.name = name;
	  }

	  public String getLocation() {
	    return location;
	  }
	  public void setLocation(String location) {
	    this.location = location;
	  }

	  public String getLang() {
	    return lang;
	  }
	  public void setLang(String lang) {
	    this.lang = lang;
	  }

	  public String getEmail() {
	    return email;
	  }
	  public void setEmail(String email) {
	    this.email = email;
	  }

	  public String getPhone() {
	    return phone;
	  }
	  public void setPhone(String phone) {
	    this.phone = phone;
	  }
	}