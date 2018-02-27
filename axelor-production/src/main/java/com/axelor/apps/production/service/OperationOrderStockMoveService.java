/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2018 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.apps.production.service;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.production.db.ManufOrder;
import com.axelor.apps.production.db.OperationOrder;
import com.axelor.apps.production.db.ProdProcessLine;
import com.axelor.apps.production.db.ProdProduct;
import com.axelor.apps.production.db.repo.OperationOrderRepository;
import com.axelor.apps.production.service.config.StockConfigProductionService;
import com.axelor.apps.stock.db.StockConfig;
import com.axelor.apps.stock.db.StockLocation;
import com.axelor.apps.stock.db.StockMove;
import com.axelor.apps.stock.db.StockMoveLine;
import com.axelor.apps.stock.db.repo.StockLocationRepository;
import com.axelor.apps.stock.db.repo.StockMoveRepository;
import com.axelor.apps.stock.service.StockMoveLineService;
import com.axelor.apps.stock.service.StockMoveService;
import com.axelor.exception.AxelorException;
import com.axelor.inject.Beans;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import org.apache.commons.collections.CollectionUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class OperationOrderStockMoveService {

	protected StockMoveService stockMoveService;
	protected StockMoveLineService stockMoveLineService;
	protected StockLocationRepository stockLocationRepo;
	
	@Inject
	public OperationOrderStockMoveService(StockMoveService stockMoveService, StockMoveLineService stockMoveLineService,
			StockLocationRepository stockLocationRepo)  {
		this.stockMoveService = stockMoveService;
		this.stockMoveLineService = stockMoveLineService;
		this.stockLocationRepo = stockLocationRepo;
	}

	public void createToConsumeStockMove(OperationOrder operationOrder) throws AxelorException {

		Company company = operationOrder.getManufOrder().getCompany();

		if(operationOrder.getToConsumeProdProductList() != null && company != null) {

			StockMove stockMove = this._createToConsumeStockMove(operationOrder, company);

			for(ProdProduct prodProduct: operationOrder.getToConsumeProdProductList()) {

				StockMoveLine stockMoveLine = this._createStockMoveLine(prodProduct, stockMove);
				stockMove.addStockMoveLineListItem(stockMoveLine);

			}

			if(stockMove.getStockMoveLineList() != null && !stockMove.getStockMoveLineList().isEmpty()){
				stockMoveService.plan(stockMove);
				operationOrder.addInStockMoveListItem(stockMove);
			}

			//fill here the consumed stock move line list item to manage the
			//case where we had to split tracked stock move lines
			if (stockMove.getStockMoveLineList() != null) {
			    for (StockMoveLine stockMoveLine : stockMove.getStockMoveLineList()) {
					operationOrder.addConsumedStockMoveLineListItem(stockMoveLine);
				}
			}
		}

	}


	protected StockMove _createToConsumeStockMove(OperationOrder operationOrder, Company company) throws AxelorException  {

		StockConfigProductionService stockConfigService = Beans.get(StockConfigProductionService.class);
		StockConfig stockConfig = stockConfigService.getStockConfig(company);
		StockLocation virtualStockLocation = stockConfigService.getProductionVirtualStockLocation(stockConfig);

		StockLocation fromStockLocation;

		ProdProcessLine prodProcessLine = operationOrder.getProdProcessLine();
		if (operationOrder.getManufOrder().getIsConsProOnOperation() && prodProcessLine != null && prodProcessLine.getStockLocation() != null) {
			fromStockLocation = prodProcessLine.getStockLocation();
		} else if (!operationOrder.getManufOrder().getIsConsProOnOperation() && prodProcessLine != null && prodProcessLine.getProdProcess() != null && prodProcessLine.getProdProcess().getStockLocation() != null) {
			fromStockLocation = prodProcessLine.getProdProcess().getStockLocation();
		} else {
			fromStockLocation = stockConfigService.getDefaultStockLocation(stockConfig);
		}

		return stockMoveService.createStockMove(null, null, company, null, fromStockLocation, virtualStockLocation,
				null, operationOrder.getPlannedStartDateT().toLocalDate(), null, null, null);

	}




	protected StockMoveLine _createStockMoveLine(ProdProduct prodProduct, StockMove stockMove) throws AxelorException  {

		return stockMoveLineService.createStockMoveLine(
				prodProduct.getProduct(),
				prodProduct.getProduct().getName(),
				prodProduct.getProduct().getDescription(),
				prodProduct.getQty(),
				prodProduct.getProduct().getCostPrice(),
				prodProduct.getUnit(),
				stockMove,
				StockMoveLineService.TYPE_IN_PRODUCTIONS, false, BigDecimal.ZERO);

	}


	public void finish(OperationOrder operationOrder) throws AxelorException  {

		List<StockMove> stockMoveList = operationOrder.getInStockMoveList();

		if(stockMoveList != null) {
			List<StockMove> stockMoveToRealizeList = stockMoveList
					.stream()
					.filter(stockMove -> stockMove.getStatusSelect() == StockMoveRepository.STATUS_PLANNED
							&& stockMove.getStockMoveLineList() != null)
					.collect(Collectors.toList());
            for (StockMove stockMove : stockMoveToRealizeList) {
				stockMoveService.realize(stockMove);
			}
		}

	}

	/**
	 * Allows to create and realize in stock moves for
	 * the given operation order. This method is used during a partial finish.
	 * @param operationOrder
	 * @throws AxelorException
	 */
	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public void partialFinish(OperationOrder operationOrder) throws AxelorException {
		ManufOrderStockMoveService manufOrderStockMoveService = Beans.get(ManufOrderStockMoveService.class);
		ManufOrder manufOrder = operationOrder.getManufOrder();
		Company company = manufOrder.getCompany();
		StockConfigProductionService stockConfigService = Beans.get(StockConfigProductionService.class);
		StockConfig stockConfig = stockConfigService.getStockConfig(company);

		StockLocation fromStockLocation;
		StockLocation toStockLocation;
		List<StockMove> stockMoveList;

		stockMoveList = operationOrder.getInStockMoveList();
		fromStockLocation = manufOrderStockMoveService.getDefaultStockLocation(manufOrder, company);
		toStockLocation = stockConfigService.getProductionVirtualStockLocation(stockConfig);

		//realize current stock move
		Optional<StockMove> stockMoveToRealize = stockMoveList.stream()
				.filter(stockMove -> stockMove.getStatusSelect() == StockMoveRepository.STATUS_PLANNED
						&& !CollectionUtils.isEmpty(stockMove.getStockMoveLineList()))
				.findFirst();
		if (stockMoveToRealize.isPresent()) {
				manufOrderStockMoveService.finishStockMove(stockMoveToRealize.get());
		}

		//generate new stock move

		StockMove newStockMove = stockMoveService.createStockMove(
				null, null, company, null,
				fromStockLocation, toStockLocation, null,
				operationOrder.getPlannedStartDateT().toLocalDate(),
				null, null, null
		);

		newStockMove.setStockMoveLineList(new ArrayList<>());
		createNewStockMoveLines(operationOrder, newStockMove);

		//plan the stockmove
		stockMoveService.plan(newStockMove);

		operationOrder.addInStockMoveListItem(newStockMove);
		newStockMove.getStockMoveLineList().forEach(operationOrder::addConsumedStockMoveLineListItem);
		operationOrder.clearDiffConsumeProdProductList();

	}

	/**
	 * Generate stock move lines after a partial finish
	 * @param operationOrder
	 * @param stockMove
	 */
	public void createNewStockMoveLines(OperationOrder operationOrder, StockMove stockMove) throws AxelorException {
		List<ProdProduct> diffProdProductList;
		Beans.get(OperationOrderService.class).updateDiffProdProductList(operationOrder);
		diffProdProductList = new ArrayList<>(operationOrder.getDiffConsumeProdProductList());
		Beans.get(ManufOrderStockMoveService.class).createNewStockMoveLines(diffProdProductList, stockMove, StockMoveLineService.TYPE_IN_PRODUCTIONS);
	}

	public void cancel(OperationOrder operationOrder) throws AxelorException {

			List<StockMove> stockMoveList = operationOrder.getInStockMoveList();

			if (stockMoveList != null) {

				for (StockMove stockMove : stockMoveList) {
					stockMoveService.cancel(stockMove);
				}

				stockMoveList.stream()
						.filter(stockMove -> stockMove.getStockMoveLineList() != null)
						.flatMap(stockMove -> stockMove.getStockMoveLineList().stream())
						.forEach(stockMoveLine -> stockMoveLine.setConsumedOperationOrder(null));
			}
	}
}

