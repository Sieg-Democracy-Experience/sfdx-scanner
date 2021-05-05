import 'reflect-metadata';
import {Messages} from '@salesforce/core';
import {FileHandler} from '../../../src/lib/util/FileHandler';
import path = require('path');
import {expect} from 'chai';
import Sinon = require('sinon');
import {PmdEngine}  from '../../../src/lib/pmd/PmdEngine'
import {uxEvents} from '../../../src/lib/ScannerEvents';
import * as TestOverrides from '../../test-related-lib/TestOverrides';
import { CUSTOM_CONFIG } from '../../../src/Constants';
import * as DataGenerator from '../eslint/EslintTestDataGenerator';

Messages.importMessagesDirectory(__dirname);
const engineMessages = Messages.loadMessages('@salesforce/sfdx-scanner', 'PmdEngine');

TestOverrides.initializeTestSetup();

describe('Tests for BasePmdEngine and PmdEngine implementation', () => {
	const testPmdEngine = new PmdEngine();

	const configFilePath = '/some/file/path/rule-ref.xml';
	const engineOptionsWithPmdCustom = new Map<string, string>([
		[CUSTOM_CONFIG.PmdConfig, configFilePath]
	]);
	const emptyEngineOptions = new Map<string, string>();

	const engineOptionsWithEslintCustom = new Map<string, string>([
		[CUSTOM_CONFIG.EslintConfig, configFilePath]
	]);

	before(async () => {
		Sinon.createSandbox();

		await testPmdEngine.init();
	});
	after(() => {
		Sinon.restore();
	});
	describe('processStdOut()', () => {
		it('Nodes that do not represent violations are filtered out of results', async () => {
			// This file contains a `file`-type node and a mix of other node types that are direct children of the `pmd`
			// node. The file nodes and the error nodes representing parser errors should be converted to violations.
			// Anything else should be filtered out of the results without problems.
			const xmlPath = path.join('test', 'code-fixtures', 'pmd-results', 'result-with-errors.txt');
			const fileHandler: FileHandler = new FileHandler();
			const xml: string = await fileHandler.readFile(xmlPath);
			expect(xml).to.not.be.null;

			const results = (testPmdEngine as any).processStdOut(xml);
			expect(results).to.be.length(3, 'Should be three result entries');
			expect(results[0].violations).to.be.length(13, 'Unexpected violation count in 1st entry');
			expect(results[1].violations).to.be.length(1, 'Unexpected violation count in 2nd entry');
			expect(results[2].violations).to.be.length(1, 'Unexpected violation count in 3rd entry');
		});
	});

	describe('processStdErr()', () => {
		it('Converts PMD\'s RuleSetNotFoundException into a more readable message', async () => {
			// This file contains the stderr created by using a custom PMD Config that referenced a misspelled category.
			// It caused a RuleSetNotFoundException, whose message is exceptionally messy.
			const stderrPath = path.join('test', 'code-fixtures', 'pmd-results', 'RuleSetNotFound-example.txt');
			const fileHandler: FileHandler = new FileHandler();
			const stderr: string = await fileHandler.readFile(stderrPath);
			expect(stderr).to.not.be.null;

			const simplifiedMessage = (testPmdEngine as any).processStdErr(stderr);
			const expectedMessage = engineMessages.getMessage('errorTemplates.rulesetNotFoundTemplate', ['category/apex/bestprctices.xml', 'ApexUnitTestClassShouldHaveAsserts']);
			expect(simplifiedMessage).to.equal(expectedMessage, 'Stderr not properly simplified');
		});

		it('If PMD\'s error matches no simplification templates, the error is returned as-is', async () => {
			// This file contains the stderr created by using a custom PMD Config that referenced a misspelled rule.
			// PMD terminated pretty gracefully, and with a straightforward error message that we want to keep.
			const stderrPath = path.join('test', 'code-fixtures', 'pmd-results', 'misspelled-rulename-example.txt');
			const fileHandler: FileHandler = new FileHandler();
			const stderr: string = await fileHandler.readFile(stderrPath);
			expect(stderr).to.not.be.null;

			const simplifiedMessage = (testPmdEngine as any).processStdErr(stderr);
			expect(simplifiedMessage).to.equal(stderr, 'No simplification should have occurred');
		});
	});

	describe('emitErrorsAndWarnings()', () => {
		it('Non file XML nodes are filtered converted to UX events', async () => {
			const expectedError = `PMD failed to evaluate against file 'Foo.java'. Message: Issue with Foo`;
			const expectedConfigError = `PMD failed to evaluate rule 'LoosePackageCoupling'. Message: No packages or classes specified`;
			const expectedSuppressedViolation = `PMD suppressed violation against file 'Bar.java'. Message: Rule suppressed message. Suppression Type: Warning. User Message: Rule user message`;
			const uxSpy = Sinon.spy(uxEvents, 'emit');

			const xmlPath = path.join('test', 'code-fixtures', 'pmd-results', 'result-with-errors.txt');
			const fileHandler: FileHandler = new FileHandler();
			const xml: string = await fileHandler.readFile(xmlPath);
			expect(xml).to.not.be.null;

			await (testPmdEngine as any).processStdOut(xml);
			Sinon.assert.callCount(uxSpy, 3);
			Sinon.assert.calledWith(uxSpy, 'warning-always', expectedConfigError);
			Sinon.assert.calledWith(uxSpy, 'warning-always', expectedError);
			Sinon.assert.calledWith(uxSpy, 'warning-always', expectedSuppressedViolation);
		});
	});

	describe('processStdout unusual cases', () => {
		it('Empty stdout', async () => {
			const results = await (testPmdEngine as any).processStdOut('');
			expect(results).to.be.not.null;
			expect(results).to.be.lengthOf(0);
		});

		it('Missing closing tag', async () => {
			const results = await (testPmdEngine as any).processStdOut('<?xml blah blah blah');
			expect(results).to.be.not.null;
			expect(results).to.be.lengthOf(0);
		});

		it('Missing opening tag', async () => {
			const results = await (testPmdEngine as any).processStdOut('blah blah blah</pmd>');
			expect(results).to.be.not.null;
			expect(results).to.be.lengthOf(0);
		});
	});

	describe('testing shouldEngineRun()', () => {
		const engine = new PmdEngine();

		before(async () => {
			await engine.init();
		});

		it('should decide to NOT run when engineOptions map contains pmdconfig', () => {
			const shouldEngineRun = engine.shouldEngineRun(
				[DataGenerator.getDummyRuleGroup()],
				[],
				[],
				engineOptionsWithPmdCustom
			);

			expect(shouldEngineRun).to.be.false;
		});

		it('should decide to NOT run when RuleGroup is empty', () => {
			const shouldEngineRun = engine.shouldEngineRun(
				[], // empty RuleGroup
				[],
				[],
				emptyEngineOptions
			);

			expect(shouldEngineRun).to.be.false;
		});

		it('should decide to run when engineOptions map does not contain pmdconfig', () => {
			const shouldEngineRun = engine.shouldEngineRun(
				[DataGenerator.getDummyRuleGroup()],
				[],
				[],
				emptyEngineOptions
			);

			expect(shouldEngineRun).to.be.true;
		});

		it('should decide to run when engineOptions map contains only eslint config', () => {
			const shouldEngineRun = engine.shouldEngineRun(
				[DataGenerator.getDummyRuleGroup()],
				[],
				[],
				engineOptionsWithEslintCustom
			);

			expect(shouldEngineRun).to.be.true;
		});
	});

	describe('tests for isEngineRequested()', () => {
		const engine = new PmdEngine();

		before(async () => {
			await engine.init();
		});

		it('should return true when custom config is not present and filter contains "pmd"', () => {
			const filteredNames = ['eslint-lwc', 'pmd'];

			const isEngineRequested = engine.isEngineRequested(filteredNames, emptyEngineOptions);

			expect(isEngineRequested).to.be.true;
		});

		it('should return false when custom config is not present but filter does not contain "pmd"', () => {
			const filteredNames = ['eslint-lwc', 'retire-js'];

			const isEngineRequested = engine.isEngineRequested(filteredNames, emptyEngineOptions);

			expect(isEngineRequested).to.be.false;
		});

		it('should return false when custom config is present even if filter contains "pmd"', () => {
			const filteredNames = ['eslint-lwc', 'pmd'];

			const isEngineRequested = engine.isEngineRequested(filteredNames, engineOptionsWithPmdCustom);

			expect(isEngineRequested).to.be.false;
		});

		it('should return false when custom config is not present and if filter contains a value that starts with "pmd"', () => {
			const filteredNames = ['eslint-lwc', 'pmd-custom'];

			const isEngineRequested = engine.isEngineRequested(filteredNames, emptyEngineOptions);

			expect(isEngineRequested).to.be.false;
		});

		it('should return true when custom config for only eslint is present and filter contains "pmd"', () => {
			const filteredNames = ['eslint-lwc', 'pmd'];

			const isEngineRequested = engine.isEngineRequested(filteredNames, engineOptionsWithEslintCustom);

			expect(isEngineRequested).to.be.true;
		});

		it('should return false when custom config exists and filter is empty', () => {
			const filteredNames = [];

			const isEngineRequested = engine.isEngineRequested(filteredNames, engineOptionsWithPmdCustom);

			expect(isEngineRequested).to.be.false;
		});

		it('should return true when custom config is not present and filter is empty', () => {
			const filteredNames = [];

			const isEngineRequested = engine.isEngineRequested(filteredNames, emptyEngineOptions);

			expect(isEngineRequested).to.be.true;
		});
	});
});
