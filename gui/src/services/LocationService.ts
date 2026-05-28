import type { LocationDto } from '@/types/api/location.dto';
import { BaseService } from './BaseService';
import type { Location } from '@/types/models/location';
import { LocationMapper } from '@/mappers/location.mapper';

export class LocationService extends BaseService {

    protected baseUrl = 'http://localhost:8080/api/v1';

    // TODO: Test purpose only
    async getAllLocations(): Promise<Location[]> {
        const locationDtos = await this.get<LocationDto[]>(`${this.baseUrl}/spatial`);
        return locationDtos.map(dto => LocationMapper.toModel(dto));
    }

}