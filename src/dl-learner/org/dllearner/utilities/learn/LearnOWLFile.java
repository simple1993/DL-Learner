/**
 * Copyright (C) 2007-2008, Jens Lehmann
 *
 * This file is part of DL-Learner.
 *
 * DL-Learner is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * DL-Learner is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.**/
package org.dllearner.utilities.learn;

import java.util.SortedSet;

import org.apache.log4j.Logger;
import org.dllearner.algorithms.refexamples.ExampleBasedROLComponent;
import org.dllearner.core.ComponentInitException;
import org.dllearner.core.ComponentManager;
import org.dllearner.core.LearningAlgorithm;
import org.dllearner.core.LearningProblem;
import org.dllearner.core.LearningProblemUnsupportedException;
import org.dllearner.core.ReasonerComponent;
import org.dllearner.core.ReasonerComponent;
import org.dllearner.kb.OWLFile;
import org.dllearner.learningproblems.PosNegDefinitionLP;

/**
 * 
 * 
 * @author Sebastian Hellmann
 * 
 * 
 */
public class LearnOWLFile {

	private static Logger logger = Logger.getLogger(LearnOWLFile.class);
	
	public LearnOWLFileConfiguration  configuration;
	
	public ComponentManager cm ;
	
	public LearnOWLFile (LearnOWLFileConfiguration configuration){ 
		this.configuration = configuration;
	}

	//return  will be replaced by List Description
	public LearningAlgorithm learn( SortedSet<String> posExamples,
			SortedSet<String> negExamples, Class<? extends ReasonerComponent> Reasoner) throws ComponentInitException,
			LearningProblemUnsupportedException {

		logger.info("Start Learning with");
		logger.info("positive examples: \t" + posExamples.size());
		logger.info("negative examples: \t" + negExamples.size());

		// the component manager is the central object to create
		// and configure components
		cm = ComponentManager.getInstance();

		// knowledge source
		OWLFile ks = cm.knowledgeSource(OWLFile.class);
		
		// reasoner
		ReasonerComponent r = cm.reasoner(Reasoner, ks);
		ReasonerComponent rs = cm.reasoningService(r);
		

		// learning problem
		LearningProblem lp = cm.learningProblem(PosNegDefinitionLP.class, rs);
		cm.applyConfigEntry(lp, "positiveExamples", posExamples);
		cm.applyConfigEntry(lp, "negativeExamples", negExamples);

		// learning algorithm
		LearningAlgorithm la = cm.learningAlgorithm(ExampleBasedROLComponent.class, lp, rs);

		
		configuration.applyConfigEntries(cm, ks, lp, rs, la);
		
		
		// all components need to be initialised before they can be used
		ks.init();
		r.init();	
		lp.init();
		la.init();
		
		return la;

	}

}
