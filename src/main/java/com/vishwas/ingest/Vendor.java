package com.vishwas.ingest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A supplier from the vendor master. The GSTIN is the identity; names can look alike, GSTINs never do. */
@Entity
@Table(name = "vendor")
public class Vendor {

    @Id
    @Column(length = 15)
    private String gstin;

    @Column(nullable = false)
    private String legalName;

    private String city;

    @Column(length = 2)
    private String stateCode;

    private String email;

    private String contactPerson;

    private String phone;

    /** What the company buys from them, e.g. "Corrugated boxes (HSN 4819)". */
    private String supplies;

    protected Vendor() {
    }

    public Vendor(String gstin, String legalName, String city, String stateCode, String email,
                  String contactPerson, String phone, String supplies) {
        this.gstin = gstin;
        this.legalName = legalName;
        this.city = city;
        this.stateCode = stateCode;
        this.email = email;
        this.contactPerson = contactPerson;
        this.phone = phone;
        this.supplies = supplies;
    }

    public String getGstin() {
        return gstin;
    }

    public String getLegalName() {
        return legalName;
    }

    public String getCity() {
        return city;
    }

    public String getStateCode() {
        return stateCode;
    }

    public String getEmail() {
        return email;
    }

    public String getContactPerson() {
        return contactPerson;
    }

    public String getPhone() {
        return phone;
    }

    public String getSupplies() {
        return supplies;
    }
}
