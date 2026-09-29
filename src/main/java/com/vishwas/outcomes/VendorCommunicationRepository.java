package com.vishwas.outcomes;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VendorCommunicationRepository extends JpaRepository<VendorCommunication, Long> {

    List<VendorCommunication> findByVendorGstinOrderByOccurredAtAsc(String gstin);

    List<VendorCommunication> findByPromiseStatus(VendorCommunication.PromiseStatus status);

    List<VendorCommunication> findAllByOrderByOccurredAtAsc();
}
