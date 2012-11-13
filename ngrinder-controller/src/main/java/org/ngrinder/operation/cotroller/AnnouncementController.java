package org.ngrinder.operation.cotroller;

import org.ngrinder.common.controller.NGrinderBaseController;
import org.ngrinder.infra.annotation.RuntimeOnlyController;
import org.ngrinder.operation.service.AnnouncementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 
 * Announcement controller.
 * 
 * @author Alex Qin
 * @since 3.1
 */
@RuntimeOnlyController
@RequestMapping("/operation/announcement")
@PreAuthorize("hasAnyRole('A', 'S')")
public class AnnouncementController extends NGrinderBaseController {
	
	@Autowired
	private AnnouncementService announcementService;
	
	/**
	 * open announcement editor.
	 * 
	 * @param model
	 * 			model.
	 * @return operation/announcement
	 */
	@RequestMapping("")
	public String openAnnounce(Model model) {
		model.addAttribute("content", announcementService.getAnnouncement());
		
		return "operation/announcement";
	}
	
	/**
	 * Save announcement.
	 * @param model
	 * 			model.
	 * @param content
	 * 			file content.
	 * @return operation/announcement
	 */
	@RequestMapping("/save")
	public String saveSystemConfiguration(Model model, @RequestParam final String content) {
		model.addAttribute("success", announcementService.saveAnnouncement(content));
		
		return openAnnounce(model);
	}
}